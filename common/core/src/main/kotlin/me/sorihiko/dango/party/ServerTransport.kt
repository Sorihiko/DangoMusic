package me.sorihiko.dango.party

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 一起听歌经当前服务器同步（局域网 / 内网穿透联机时服务器就是房主的游戏，里面跑着 [PartyServer]）。
 * 和 [Firebase] 一样的读写 / 监听方式，[ListenParty] 不用区分；不用 Firebase、不用代理。
 * 每次进服务器先打个招呼（[ready]），服务器有这个 mod、版本对得上才用它。
 */
object ServerTransport : PartyTransport {

    private const val TIMEOUT_MS = 5_000L
    /** 打招呼等多久：局域网很快；服务器没有这个 mod 时（1.8.9 发之前看不出来）不要让人干等 */
    private const val HELLO_TIMEOUT_MS = 2_000L

    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val subs = AtomicInteger()
    private val listeners = ConcurrentHashMap<Int, (PartyEvent) -> Unit>()

    /** 服务器给的身份（玩家的 UUID）；没打过招呼是空的 */
    @Volatile private var myUid = ""
    @Volatile private var clockOffset = 0L
    /** 这次进服务器打招呼的结果：null = 还没试，true / false = 能不能用 */
    @Volatile private var hello: Boolean? = null
    @Volatile private var helloAt = 0L

    override val configured: Boolean get() = PartyNet.link != null
    override val uid: String get() = myUid
    @Volatile override var lastStatus: Int = 0
        private set
    @Volatile override var lastFailure: PartyTransport.Failure = PartyTransport.Failure.NONE
        private set
    override val route: PartyTransport.Route get() = PartyTransport.Route.DIRECT

    /**
     * 当前服务器能不能用来同步：没试过（或者上次没成功、过了一会儿）就打个招呼，最多等 [TIMEOUT_MS]。
     * 会卡住调用的线程，不要在渲染线程调
     */
    fun ready(): Boolean {
        hello?.let { ok -> if (ok || System.currentTimeMillis() - helloAt < 10_000L) return ok }
        helloAt = System.currentTimeMillis()
        val r = request(JsonObject().apply { addProperty("op", "hello") }, HELLO_TIMEOUT_MS)
        val ok = r != null && r.get("protocol")?.takeIf { it.isJsonPrimitive }?.asInt == PartyNet.PROTOCOL &&
            !r.get("uid")?.takeIf { it.isJsonPrimitive }?.asString.isNullOrEmpty()
        if (ok) {
            myUid = r!!.get("uid").asString
            r.get("now")?.takeIf { it.isJsonPrimitive }?.asLong?.let { clockOffset = it - System.currentTimeMillis() }
        }
        hello = ok
        return ok
    }

    /** 上次打招呼成功了（界面显示用，不会发请求） */
    val connected: Boolean get() = hello == true

    // ---------------------------------------------------------------- 收发

    private fun request(body: JsonObject, timeout: Long = TIMEOUT_MS): JsonObject? {
        val id = ids.incrementAndGet()
        body.addProperty("id", id)
        val f = CompletableFuture<JsonObject>()
        pending[id] = f
        try {
            if (!PartyNet.sendToServer(body)) {
                lastStatus = -1
                lastFailure = PartyTransport.Failure.SERVER_UNREACHABLE
                return null
            }
            val r = f.get(timeout, TimeUnit.MILLISECONDS)
            lastStatus = r.get("status")?.takeIf { it.isJsonPrimitive }?.asInt ?: 500
            if (lastStatus !in 200..299) {
                lastFailure = PartyTransport.Failure.REJECTED
                return null
            }
            lastFailure = PartyTransport.Failure.NONE
            return r
        } catch (_: Exception) {
            lastStatus = -1
            lastFailure = PartyTransport.Failure.SERVER_UNREACHABLE
            return null
        } finally {
            pending.remove(id)
        }
    }

    private fun op(op: String, path: String, value: JsonElement? = null, etag: String? = null) = request(JsonObject().apply {
        addProperty("op", op)
        addProperty("path", path)
        if (value != null) add("value", value)
        if (etag != null) addProperty("etag", etag)
    })

    /** [PartyNet]：服务器发来一条完整的消息 */
    internal fun onMessage(msg: JsonObject) {
        msg.get("re")?.takeIf { it.isJsonPrimitive }?.asLong?.let { id ->
            pending[id]?.complete(msg)
            return
        }
        val sub = msg.get("ev")?.takeIf { it.isJsonPrimitive }?.asInt ?: return
        val data = msg.get("data")?.takeUnless { it.isJsonNull }
        listeners[sub]?.invoke(PartyEvent(PartyEvent.Type.PUT, "/", data))
    }

    /** 离开了服务器：等着的请求都算失败，下次进服务器重新打招呼；在这个服务器上的房间也没了 */
    internal fun disconnected() {
        hello = null
        myUid = ""
        listeners.clear()
        for (f in pending.values) f.completeExceptionally(IllegalStateException("disconnected"))
        pending.clear()
        ListenParty.serverLinkLost()
    }

    // ---------------------------------------------------------------- PartyTransport

    override fun ensureAuth(): Boolean = ready()

    override fun syncClock() {
        val r = request(JsonObject().apply { addProperty("op", "time") }) ?: return
        r.get("now")?.takeIf { it.isJsonPrimitive }?.asLong?.let { clockOffset = it - System.currentTimeMillis() }
    }

    override fun serverNow(): Long = System.currentTimeMillis() + clockOffset

    override fun serverTimestamp(): JsonElement = JsonParser().parse("""{".sv":"timestamp"}""")

    override fun get(path: String): JsonElement? = op("get", path)?.get("value")

    override fun getWithEtag(path: String): PartyTransport.Snapshot? {
        val r = op("get", path) ?: return null
        return PartyTransport.Snapshot(r.get("value")?.takeUnless { it.isJsonNull }, r.get("etag")?.asString.orEmpty())
    }

    override fun putIfUnchanged(path: String, value: JsonElement, etag: String): JsonElement? = op("putIf", path, value, etag)?.get("value")

    override fun put(path: String, value: JsonElement): JsonElement? = op("put", path, value)?.let { it.get("value") ?: JsonPrimitive(true) }

    override fun patch(path: String, value: JsonElement): JsonElement? = op("patch", path, value)?.let { it.get("value") ?: JsonPrimitive(true) }

    override fun delete(path: String): Boolean = op("delete", path) != null

    override fun listen(path: String, onEvent: (PartyEvent) -> Unit): () -> Unit {
        val sub = subs.incrementAndGet()
        listeners[sub] = onEvent
        // 订阅本身在后台发：服务器收到后会先推一份现在的数据
        Thread({
            if (request(JsonObject().apply { addProperty("op", "listen"); addProperty("path", path); addProperty("sub", sub) }) == null) {
                me.sorihiko.dango.DangoLogger.warn("Listen Together: server listen failed ({})", lastStatus)
            }
        }, "Dango-PartyListen").apply { isDaemon = true }.start()
        return {
            listeners.remove(sub)
            Thread({ request(JsonObject().apply { addProperty("op", "unlisten"); addProperty("sub", sub) }) }, "Dango-PartyUnlisten")
                .apply { isDaemon = true }.start()
        }
    }
}
