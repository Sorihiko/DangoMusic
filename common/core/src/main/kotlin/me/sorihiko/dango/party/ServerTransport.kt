package me.sorihiko.dango.party

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.platform.PartyLink

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * 走 Minecraft 服务器的 [PartyTransport]：请求打包给服务器，服务器用一个内存里的 JSON 树当数据库。
 *
 * 服务器那一侧实现在 [me.sorihiko.dango.party.server.PartyStore]，平台层只负责把包送过去、送回来
 * （目前是 Forge 1.20.1 的 [PartyLink]）。没有服务器（或服务器没装本 mod）时 [configured] 为 false，
 * [ListenParty] 会退回 Firebase。
 *
 * 线程：所有方法都在一起听的工作线程（或创建 / 加入房间的线程）上调用，可以阻塞等回包；
 * 发包由平台层挪回游戏主线程，收包在网络线程上完成这里等待的 future。
 */
object ServerTransport : PartyTransport {

    /** 等服务器回话最多多久。超过就当作掉线，让上层给出提示 */
    private const val TIMEOUT_MS = 5000L

    private class Response(val status: Int, val serverTime: Long, val etag: String, val body: String)

    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<Response>>()
    private val sentAt = ConcurrentHashMap<Long, Long>()
    private val listeners = ConcurrentHashMap<Long, (PartyEvent) -> Unit>()

    /** 已经挂上回调的那个 link（换了服务器要重新挂） */
    @Volatile private var wired: PartyLink? = null

    /** 服务器时间 - 本机时间，收到回包时按往返中点校准 */
    @Volatile private var clockOffset = 0L

    @Volatile override var lastStatus = 0
        private set
    @Volatile override var lastFailure = PartyTransport.Failure.NONE
        private set

    // ---------------------------------------------------------------- 基本状态

    private fun link(): PartyLink? = try {
        DangoMusic.instance.platform.partyLink
    } catch (_: Throwable) {
        null
    }

    /** 服务器装了这个 mod，并且协议对得上 */
    override val configured: Boolean
        get() {
            val l = link() ?: return false
            return l.available && l.protocol == PartyProtocol.VERSION
        }

    /** 服务器在线但协议号不一样（两端版本不一致，不能用） */
    val protocolMismatch: Boolean
        get() {
            val l = link() ?: return false
            return l.available && l.protocol != PartyProtocol.VERSION
        }

    /** 服务器声明的协议号（0 = 还没收到信息） */
    val serverProtocol: Int get() = link()?.protocol ?: 0

    /** 服务器显示名（界面用） */
    val serverName: String get() = link()?.serverName ?: ""

    override val uid: String get() = link()?.playerId ?: ""

    override val route: PartyTransport.Route get() = PartyTransport.Route.DIRECT

    /** 收到服务器信息（登录 / 换服务器）时重置时钟，别把上一个服务器的时间差带过来 */
    fun resetClock() {
        clockOffset = 0L
    }

    // ---------------------------------------------------------------- 时钟

    override fun serverNow(): Long = System.currentTimeMillis() + clockOffset

    override fun serverTimestamp(): JsonElement = JsonParser().parse("""{".sv":"timestamp"}""")

    override fun syncClock() {
        request(PartyProtocol.OP_PING, "", "", null) ?: return
    }

    /** 用「发出去到收到」的中点估算时差，比直接用收到的时间准一点 */
    private fun applyClock(sent: Long, r: Response) {
        val mid = sent + (System.currentTimeMillis() - sent) / 2
        clockOffset = r.serverTime - mid
    }

    // ---------------------------------------------------------------- 认证

    override fun ensureAuth(): Boolean {
        val l = link()
        if (l == null || !l.available) {
            lastStatus = -1
            lastFailure = PartyTransport.Failure.SERVER_UNREACHABLE
            return false
        }
        if (l.protocol != PartyProtocol.VERSION) {
            lastStatus = -1
            lastFailure = PartyTransport.Failure.REJECTED
            return false
        }
        lastFailure = PartyTransport.Failure.NONE
        return true
    }

    // ---------------------------------------------------------------- 数据库

    override fun get(path: String): JsonElement? {
        val r = request(PartyProtocol.OP_GET, path, "", null) ?: return null
        return parse(r.body)
    }

    override fun getWithEtag(path: String): PartyTransport.Snapshot? {
        val r = request(PartyProtocol.OP_GET, path, "", null) ?: return null
        val value = parse(r.body)
        return PartyTransport.Snapshot(value?.takeUnless { it.isJsonNull }, r.etag)
    }

    override fun putIfUnchanged(path: String, value: JsonElement, etag: String): JsonElement? {
        val r = request(PartyProtocol.OP_PUT, path, etag, value.toString()) ?: return null
        return parse(r.body)
    }

    override fun put(path: String, value: JsonElement): JsonElement? {
        val r = request(PartyProtocol.OP_PUT, path, "", value.toString()) ?: return null
        return parse(r.body)
    }

    override fun patch(path: String, value: JsonElement): JsonElement? {
        val r = request(PartyProtocol.OP_PATCH, path, "", value.toString()) ?: return null
        return parse(r.body)
    }

    override fun delete(path: String): Boolean {
        request(PartyProtocol.OP_DELETE, path, "", null) ?: return false
        return true
    }

    // ---------------------------------------------------------------- 订阅

    /**
     * 订阅一个路径。服务器会先把当前数据当成一条 PUT "/" 推过来（和 Firebase 首帧的行为一样），
     * 之后的变化由平台层转过来。返回停止订阅的函数。
     */
    override fun listen(path: String, onEvent: (PartyEvent) -> Unit): () -> Unit {
        val subId = ids.getAndIncrement()
        listeners[subId] = onEvent
        if (request(PartyProtocol.OP_SUBSCRIBE, path, "", null, subId) == null) {
            listeners.remove(subId)
            return {}
        }
        return {
            listeners.remove(subId)
            // 退订不等回包：调用方可能在游戏主线程（退出房间 / 退服）
            link()?.let { l ->
                try {
                    l.send(PartyProtocol.OP_UNSUBSCRIBE, subId, path, "", "")
                } catch (_: Throwable) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- 收发

    private fun wire(l: PartyLink) {
        if (wired === l) return
        wired = l
        l.bind(object : PartyLink.Handler {
            override fun onResponse(id: Long, status: Int, serverTime: Long, etag: String, body: String) {
                val future = pending[id] ?: return
                val sent = sentAt[id] ?: System.currentTimeMillis()
                applyClock(sent, Response(status, serverTime, etag, body))
                future.complete(Response(status, serverTime, etag, body))
            }

            override fun onEvent(subId: Long, path: String, data: String) {
                val element = try {
                    JsonParser().parse(data)
                } catch (_: Throwable) {
                    null
                }
                if (element == null) return
                deliver(subId, path, element)
            }
        })
    }

    private fun deliver(subId: Long, path: String, data: JsonElement?) {
        val callback = listeners[subId] ?: return
        try {
            callback(PartyEvent(PartyEvent.Type.PUT, path, data?.takeUnless { it.isJsonNull }))
        } catch (e: Throwable) {
            DangoLogger.warn("Listen Together: push handler failed: {}", e.toString())
        }
    }

    private fun request(op: Int, path: String, etag: String, body: String?, id: Long = ids.getAndIncrement()): Response? {
        val l = link()
        if (l == null || !ensureAuth()) return null
        if (!l.available) {
            lastStatus = -1
            lastFailure = PartyTransport.Failure.SERVER_UNREACHABLE
            return null
        }
        wire(l)
        if (path.length > PartyProtocol.MAX_PATH) {
            lastStatus = 400
            lastFailure = PartyTransport.Failure.REJECTED
            return null
        }
        if (body != null && body.length > PartyProtocol.MAX_BODY) {
            lastStatus = 413
            lastFailure = PartyTransport.Failure.REJECTED
            return null
        }
        val future = CompletableFuture<Response>()
        pending[id] = future
        sentAt[id] = System.currentTimeMillis()
        try {
            if (!l.send(op, id, path, etag, body ?: "")) {
                lastStatus = -1
                lastFailure = PartyTransport.Failure.SERVER_UNREACHABLE
                return null
            }
            val r = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            lastStatus = r.status
            if (r.status !in 200..299) {
                lastFailure = PartyTransport.Failure.REJECTED
                return null
            }
            lastFailure = PartyTransport.Failure.NONE
            return r
        } catch (e: TimeoutException) {
            DangoLogger.warn("Listen Together: server timeout {} {}", PartyProtocol.opName(op), path)
            lastStatus = -1
            lastFailure = PartyTransport.Failure.SERVER_UNREACHABLE
            return null
        } catch (e: Exception) {
            DangoLogger.warn("Listen Together: server request failed {} {}: {}", PartyProtocol.opName(op), path, e.toString())
            lastStatus = -1
            lastFailure = PartyTransport.Failure.SERVER_UNREACHABLE
            return null
        } finally {
            pending.remove(id)
            sentAt.remove(id)
        }
    }

    private fun parse(text: String): JsonElement? = try {
        JsonParser().parse(text.ifBlank { "null" })
    } catch (_: Throwable) {
        null
    }
}
