package me.sorihiko.dango.party

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.music.MusicSource

import java.security.SecureRandom
import java.util.ArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 一起听歌（通过 [PartyTransport] 同步）：
 *   房主：开房间 → 得到房间码；自己正常放歌，切歌 / 拖进度 / 暂停时把状态写到房间
 *   成员：输入房间码加入 → 监听房间，自动放同一首歌、跳到同一位置、跟着暂停
 *   成员控制：房主开着"允许成员控制"时，成员切歌 / 拖进度 / 暂停会写一条请求（[PartyRequest]），
 *     房主的游戏自动执行（不用房主确认），再照常把新状态发给所有人。播放状态始终只有房主在写。
 * 每个人按歌曲音源从自己的账号取歌，只同步歌曲信息、进度和播放状态。
 *
 * 同步层有两条：服务器装了本 mod 时走 [ServerTransport]（不依赖 Firebase、不用代理），
 * 否则退回 [Firebase]。房间数据格式两边一样，所以界面和逻辑不用关心走哪条。
 *
 * 数据结构：rooms/{房间码} = { host, hostName, memberControl, members/{uid} = {name, lastSeen},
 *   state = {...}, queue = {index, tracks:[...]}, req = {...} }
 * queue 是房主的整个播放列表：房主变化时写一次，成员据此看到完整的「正在播放」，点下一首也对得上。
 */
object ListenParty {

    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val CODE_LENGTH = 5
    private const val ABANDONED_MS = 11 * 60_000L
    private const val HEARTBEAT_MS = 30_000L
    /** 房主没有变化时多久重发一次状态（兜底，正常的切歌 / 暂停 / 拖进度会立刻发） */
    private const val STATE_REFRESH_MS = 30_000L
    /** 成员多久没心跳算离开，至少要比 [HEARTBEAT_MS] 多留几轮 */
    private const val MEMBER_TIMEOUT_MS = 90_000L
    /** 同步给成员的播放列表最多几首（太长的歌单传不动，也没有意义） */
    private const val MAX_QUEUE = 200
    /** 一次发布的播放列表大约最多多少字节（服务器单次请求上限 256KB） */
    private const val MAX_QUEUE_BYTES = 180_000

    enum class Role { NONE, HOST, MEMBER }

    class Member(val uid: String, val name: String, val lastSeen: Long)

    private class RemoteState(
        val track: NeteaseTrack,
        val pos: Long, val playing: Boolean, val at: Long,
        /** 房主的整个播放列表（可能为空：房主版本较旧或还没发布过） */
        val queue: List<NeteaseTrack> = emptyList(),
        /** [queue] 的指纹，用来判断要不要重新套用到本地 */
        val queueKey: String = "",
    )

    @Volatile var role = Role.NONE
        private set
    @Volatile var code = ""
        private set
    @Volatile var hostName = ""
        private set
    @Volatile var hostUid = ""
        private set
    @Volatile var members: List<Member> = emptyList()
        private set
    /** 房间是否允许成员控制；旧版房主开的房间没有这一项，算 null */
    @Volatile var memberControl: Boolean? = null
        private set
    /** 成员现在能不能控制播放 */
    val canControl: Boolean get() = role == Role.MEMBER && memberControl == true
    /** 给界面看的状态 / 错误信息 */
    @Volatile var status = ""
        private set
    @Volatile var busy = false
        private set

    /**
     * 同步层：连接方式选了「Server」、并且服务器装了这个 mod 时走服务器
     * （不碰 Firebase，也不用代理）；否则退回 Firebase。
     * 服务器在线但协议号不一样时仍然用 ServerTransport，
     * 这样界面能明确提示「两端版本不一致」，而不是悄悄换成 Firebase。
     *
     * 注意这里看的是连接方式，而不是像原来那样「服务器能用就用」：
     * Direct 现在就是「走服务器」，玩家在界面上能自己选，也就能切回 Firebase
     * （系统代理 / 手动填代理）。
     */
    val transport: PartyTransport
        get() = if (PartyProxy.currentMode() == PartyProxy.Mode.DIRECT &&
            (ServerTransport.configured || ServerTransport.protocolMismatch)) ServerTransport
        else Firebase

    @Volatile private var remote: RemoteState? = null
    private var stopListen: (() -> Unit)? = null
    private val mirror = RoomMirror()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-Party").apply { isDaemon = true } }
    private val refreshPending = AtomicBoolean(false)
    private val rng = SecureRandom()

    // 房主：上一次发出去的状态 / 播放列表
    private var sentKey = ""
    /** 上一次发布出去的播放列表（queue 每次变化都会换一个新 List，比内容更便宜） */
    private var publishedQueue: List<NeteaseTrack>? = null
    private var publishedIndex = -1
    /** 发布失败后多久再试 */
    private var queueRetryAt = 0L
    @Volatile private var roomProtocol = 1
    private var sentPlaying = false
    private var sentPos = 0L
    private var sentAt = 0L
    // 成员：已经请求加载的歌、上次纠正的时间、已经套用过的播放列表
    @Volatile private var requestedKey = ""
    @Volatile private var lastCorrection = 0L
    @Volatile private var appliedQueueKey = ""
    private var lastHeartbeat = 0L
    // 房主：最后处理过的成员请求；还没执行的请求
    private var lastReqId = ""
    @Volatile private var pendingReq: PartyRequest? = null

    init {
        Thread({
            while (true) {
                try {
                    Thread.sleep(300)
                    when (role) {
                        Role.HOST -> hostTick()
                        Role.MEMBER -> followTick()
                        Role.NONE -> {}
                    }
                    if (role != Role.NONE && System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_MS) heartbeat()
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    DangoLogger.warn("Listen party tick failed: {}", e.toString())
                }
            }
        }, "Dango-PartyTick").apply { isDaemon = true }.start()
        Runtime.getRuntime().addShutdownHook(Thread { if (role != Role.NONE) leaveNow() })
    }

    private fun myName(): String = try { DangoMusic.instance.platform.playerName } catch (_: Exception) { "Player" }

    private fun sv(): JsonElement = transport.serverTimestamp()

    private fun newCode(): String =
        "D-" + (1..CODE_LENGTH).map { CODE_ALPHABET[rng.nextInt(CODE_ALPHABET.length)] }.joinToString("")

    fun supportsSource(source: MusicSource): Boolean = source == MusicSource.NETEASE ||
        (source == MusicSource.QQ && (role == Role.NONE || roomProtocol >= 2)) ||
        (source == MusicSource.KUGOU && (role == Role.NONE || roomProtocol >= 3))

    /**
     * 房主超过 [ABANDONED_MS] 没心跳的房间算废弃，可以被新房间接管。
     * 数据库规则里是 10 分钟，这里多留 1 分钟，免得两边时钟差一点就被规则拒绝。
     */
    private fun isAbandoned(room: JsonObject): Boolean {
        val host = room.get("host")?.takeIf { it.isJsonPrimitive }?.asString ?: return false
        val seen = ((room.get("members") as? JsonObject)?.get(host) as? JsonObject)
            ?.get("lastSeen")?.takeIf { it.isJsonPrimitive }?.asLong ?: return false
        return transport.serverNow() - seen > ABANDONED_MS
    }

    // ---------------------------------------------------------------- 开房 / 加入 / 离开

    fun create() {
        if (busy || role != Role.NONE) return
        busy = true
        status = "正在创建房间…"
        worker.execute {
            try {
                val net = transport
                if (!net.configured) return@execute fail(notReady(net))
                if (!net.ensureAuth()) return@execute fail(connectError(net, "连不上服务器（" + net.lastStatus + "）"))
                net.syncClock()
                repeat(8) {
                    val c = newCode()
                    // 先看这个码有没有人在用：没人用，或者房主已经很久没心跳（崩溃 / 直接关游戏留下的），才能拿来用
                    val snap = net.getWithEtag("rooms/$c")
                    if (snap == null) {
                        // 网络不通就别再换 7 个码挨个超时了
                        if (net.lastStatus == -1) return@execute fail(connectError(net, "创建房间失败（-1）"))
                        return@repeat
                    }
                    val existing = snap.value as? JsonObject
                    if (existing != null && !isAbandoned(existing)) return@repeat
                    val room = JsonObject().apply {
                        addProperty("protocol", 3)
                        addProperty("host", net.uid)
                        addProperty("hostName", myName())
                        addProperty("memberControl", true)
                        add("createdAt", sv())
                        add("members", JsonObject().apply {
                            add(net.uid, JsonObject().apply {
                                addProperty("name", myName())
                                add("lastSeen", sv())
                            })
                        })
                    }
                    // 带上刚才读到的版本号：这期间要是别人抢先建了同一个码，写入会失败（412），换个码再试
                    if (net.putIfUnchanged("rooms/$c", room, snap.etag) != null) {
                        code = c
                        hostUid = net.uid
                        hostName = myName()
                        role = Role.HOST
                        roomProtocol = 3
                        sentKey = ""
                        publishedQueue = null
                        publishedIndex = -1
                        memberControl = true
                        lastReqId = ""
                        pendingReq = null
                        lastHeartbeat = System.currentTimeMillis()
                        startListening()
                        status = ""
                        return@execute
                    }
                }
                fail(connectError(net, "创建房间失败（" + net.lastStatus + "）"))
            } finally {
                busy = false
            }
        }
    }

    fun join(input: String) {
        val c = input.trim().uppercase()
        if (busy || role != Role.NONE || c.isEmpty()) return
        busy = true
        status = "正在加入 $c …"
        worker.execute {
            try {
                val net = transport
                if (!net.configured) return@execute fail(notReady(net))
                if (!net.ensureAuth()) return@execute fail(connectError(net, "连不上服务器（" + net.lastStatus + "）"))
                net.syncClock()
                val room = net.get("rooms/$c")
                    // 读失败（null）和房间不存在（读到空值）分开说
                    ?: return@execute fail(connectError(net, "加入失败（" + net.lastStatus + "）"))
                if (!room.isJsonObject) return@execute fail("房间 $c 不存在")
                if (isAbandoned(room.asJsonObject)) return@execute fail("房间 $c 已经没人了")
                val protocol = room.asJsonObject.get("protocol")?.asInt ?: 1
                if (protocol !in 1..3) return@execute fail("This room requires a newer DangoMusic version.")
                val me = JsonObject().apply {
                    addProperty("name", myName())
                    add("lastSeen", sv())
                }
                if (net.put("rooms/$c/members/${net.uid}", me) == null) {
                    return@execute fail(connectError(net, "加入失败（" + net.lastStatus + "）"))
                }
                code = c
                role = Role.MEMBER
                roomProtocol = protocol
                requestedKey = ""
                appliedQueueKey = ""
                MusicPlayer.followMode = true
                lastHeartbeat = System.currentTimeMillis()
                apply(room.asJsonObject)
                startListening()
                status = ""
            } finally {
                busy = false
            }
        }
    }

    fun leave() {
        if (role == Role.NONE) return
        worker.execute { leaveNow() }
    }

    private fun leaveNow() {
        val c = code
        val wasHost = role == Role.HOST
        abandonLocally()
        try {
            val net = transport
            if (wasHost) net.delete("rooms/$c") else net.delete("rooms/$c/members/${net.uid}")
        } catch (_: Exception) {
        }
    }

    /** 只清本地状态，不动数据库 */
    private fun abandonLocally() {
        stopListen?.invoke()
        stopListen = null
        mirror.clear()
        role = Role.NONE
        code = ""
        hostUid = ""
        hostName = ""
        members = emptyList()
        remote = null
        memberControl = null
        pendingReq = null
        roomProtocol = 1
        requestedKey = ""
        appliedQueueKey = ""
        sentKey = ""
        publishedQueue = null
        publishedIndex = -1
        queueRetryAt = 0L
        MusicPlayer.followMode = false
    }

    /**
     * 断线 / 退服：房间数据在服务器上，服务端会把我们移出成员列表，
     * 这里只管本机——退出房间；是否停止播放看设置（stopOnLeaveWorld，默认停）。
     */
    fun onSessionEnded() {
        val wasInRoom = role != Role.NONE
        if (role != Role.NONE) abandonLocally()
        if (MusicPlayer.stopOnLeaveWorld) MusicPlayer.stop()
        ServerTransport.resetClock()
        if (wasInRoom) status = "Disconnected from the server"
    }

    /** 连上服务器：清掉上一个服务器留下的状态和时差 */
    fun onSessionStarted() {
        ServerTransport.resetClock()
        if (role == Role.NONE) status = ""
    }

    /** 成员：重新和房主对齐（自己点了别的歌之后想回来时用） */
    fun resync() {
        requestedKey = ""
        lastCorrection = 0L
    }

    /** 房主：打开 / 关闭"允许成员控制" */
    fun setMemberControl(on: Boolean) {
        if (role != Role.HOST) return
        val c = code
        memberControl = on
        worker.execute {
            if (transport.patch("rooms/$c", JsonObject().apply { addProperty("memberControl", on) }) == null && code == c) {
                status = connectError(transport, "设置失败（" + transport.lastStatus + "）")
            }
        }
    }

    // ---------------------------------------------------------------- 成员：把操作交给房主

    /** 成员点了一首歌：本地先开始加载（快一点），同时请房主切过去 */
    fun requestPlay(track: NeteaseTrack) {
        if (!canControl || !supportsSource(track.source)) return
        // 队列里已经有同一首：不再重复入列。以前连点两次会让房主放两遍，队列里也多一首
        if (MusicPlayer.queue.any { it.key == track.key }) {
            MusicPlayer.hint("Already in the queue")
            return
        }
        requestedKey = track.key
        MusicPlayer.playTrackAt(track, { 0L }, false)
        lastCorrection = System.currentTimeMillis() + 4000
        sendRequest(PartyRequest.PLAY, 0L, track)
    }

    /**
     * 成员点了「播放全部」：把整个列表交给房主。
     * 原来的 PLAY 请求只带一首，所以只会过去第一首；这里把整列一起发，房主收到后整列替换。
     */
    fun requestPlayList(tracks: List<NeteaseTrack>, startIndex: Int) {
        if (!canControl) return
        val list = tracks.take(MAX_QUEUE)
        if (list.isEmpty()) return
        val first = list[Math.floorMod(startIndex, list.size)]
        if (!supportsSource(first.source)) return
        requestedKey = first.key
        MusicPlayer.playTrackAt(first, { 0L }, false)
        lastCorrection = System.currentTimeMillis() + 4000
        val c = code
        worker.execute {
            val net = transport
            val r = PartyRequest(newCode(), net.uid, myName(), PartyRequest.PLAY, 0L, 0L, first, list)
            if (net.put("rooms/$c/req", PartyRequest.write(r, sv())) == null && code == c) {
                status = if (net !== ServerTransport && (net.lastStatus == 401 || net.lastStatus == 403))
                    "成员控制被数据库拒绝了，请房间所在的 Firebase 更新数据库规则"
                else connectError(net, "操作没发出去（" + net.lastStatus + "）")
                resync()
            }
        }
    }

    fun requestNext() = sendRequest(PartyRequest.NEXT, 0L, null)
    fun requestPrev() = sendRequest(PartyRequest.PREV, MusicPlayer.positionMs, null)

    /** 拖进度 / 暂停 / 继续：本地已经先生效了，这里只是告诉房主，并在房主的新状态到之前先别把自己拉回去 */
    fun requestState(op: String, pos: Long) {
        lastCorrection = System.currentTimeMillis() + 2500
        sendRequest(op, pos, null)
    }

    private fun sendRequest(op: String, pos: Long, track: NeteaseTrack?) {
        if (!canControl) return
        val c = code
        worker.execute {
            val net = transport
            val r = PartyRequest(newCode(), net.uid, myName(), op, pos, 0L, track)
            if (net.put("rooms/$c/req", PartyRequest.write(r, sv())) == null && code == c) {
                status = if (net !== ServerTransport && (net.lastStatus == 401 || net.lastStatus == 403))
                    "成员控制被数据库拒绝了，请房间所在的 Firebase 更新数据库规则"
                else connectError(net, "操作没发出去（" + net.lastStatus + "）")
                resync()
            }
        }
    }

    /** 还没准备好开房 / 加入：服务器没装本 mod，或 Firebase 没配置 */
    private fun notReady(net: PartyTransport): String =
        if (net === ServerTransport) {
            if (ServerTransport.protocolMismatch) "Server runs a different Dango Music version. Update both sides"
            else "This server does not have Dango Music. Listen Together needs it on the server"
        } else "Firebase is not configured (firebase.properties)"

    /** 连不上时告诉玩家卡在哪：服务器、登录服务、数据库还是代理；服务器拒绝等其他情况用 [fallback] */
    private fun connectError(net: PartyTransport, fallback: String): String {
        if (net === ServerTransport) {
            if (ServerTransport.protocolMismatch) return "Server runs a different Dango Music version. Update both sides"
            return when (net.lastStatus) {
                -1 -> "The server did not answer. Check your connection"
                403 -> "The server refused the request"
                else -> fallback
            }
        }
        return when (net.lastFailure) {
            PartyTransport.Failure.AUTH_UNREACHABLE -> "连不上 Google 登录服务。国内网络通常需要开代理，可在“连接方式”里设置"
            PartyTransport.Failure.SERVER_UNREACHABLE -> "连不上 Firebase 数据库。国内网络通常需要开代理，可在“连接方式”里设置"
            PartyTransport.Failure.PROXY_UNREACHABLE -> "代理连不上。请确认代理软件已打开、端口正确，或在“连接方式”里换一种"
            PartyTransport.Failure.RELAY_UNREACHABLE -> "中转和直连 Firebase 都连不上。请检查网络，或在“连接方式”里开代理"
            else -> fallback
        }
    }

    private fun fail(msg: String) {
        status = msg
        busy = false
    }

    // ---------------------------------------------------------------- 房间变化

    private fun startListening() {
        stopListen?.invoke()
        mirror.clear()
        val c = code
        stopListen = transport.listen("rooms/$c") { e ->
            // 已经退出 / 换了房间时，旧连接最后推来的一条不要混进来
            if (code != c) return@listen
            mirror.apply(e)
            // 变化可能一下来好几条（比如几个人的心跳），合并成一次处理
            if (refreshPending.compareAndSet(false, true)) {
                worker.execute {
                    refreshPending.set(false)
                    if (code != c || role == Role.NONE) return@execute
                    onRoomChanged()
                }
            }
        }
    }

    private fun onRoomChanged() {
        val gone = mirror.read { room ->
            when {
                room == null -> "closed"
                (room.get("host")?.takeIf { it.isJsonPrimitive }?.asString ?: "").let { hostUid.isNotEmpty() && it != hostUid } -> "taken"
                else -> {
                    apply(room)
                    null
                }
            }
        }
        when (gone) {
            "closed" -> if (role == Role.MEMBER) {
                leaveNow()
                status = "房主关闭了房间"
            }
            // 掉线太久，房间码已经被别人的新房间接管了：本地退出，别去碰别人的房间
            "taken" -> {
                abandonLocally()
                status = "房间已失效（掉线太久），请重新创建或加入"
            }
        }
    }

    private fun apply(room: JsonObject) {
        hostUid = room.get("host")?.asString ?: ""
        hostName = room.get("hostName")?.asString ?: ""
        memberControl = room.get("memberControl")?.takeIf { it.isJsonPrimitive }?.asBoolean
        val now = transport.serverNow()
        members = (room.get("members") as? JsonObject)?.entrySet()?.mapNotNull { (uid, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val seen = o.get("lastSeen")?.let { if (it.isJsonPrimitive) it.asLong else 0L } ?: 0L
            if (now - seen > MEMBER_TIMEOUT_MS) null else Member(uid, o.get("name")?.asString ?: "?", seen)
        }?.sortedBy { if (it.uid == hostUid) 0 else 1 } ?: emptyList()

        val st = room.get("state") as? JsonObject
        val (queue, queueKey) = readQueue(room.get("queue") as? JsonObject)
        remote = if (st == null) null else try {
            val track = PartyTrackCodec.read(st)
            if (track == null || !supportsSource(track.source)) null else RemoteState(
                track = track,
                pos = st.get("pos")?.asLong ?: 0L,
                playing = st.get("playing")?.asBoolean ?: false,
                at = st.get("at")?.asLong ?: now,
                queue = queue,
                queueKey = queueKey,
            )
        } catch (_: Exception) {
            null
        }

        if (role == Role.HOST) {
            val r = PartyRequest.read(room.get("req") as? JsonObject)
            if (PartyRequest.accept(r, lastReqId, hostUid, members.map { it.uid }, memberControl == true, now)) {
                lastReqId = r!!.id
                pendingReq = r
            } else if (r != null && lastReqId.isEmpty()) {
                // 刚开房 / 刚收到第一条快照时留着的旧请求：记下来，不执行
                lastReqId = r.id
            }
        }
    }

    /** 房间里的播放列表：房主写、成员读。返回（列表，指纹）；没有就是空 */
    private fun readQueue(data: JsonObject?): Pair<List<NeteaseTrack>, String> {
        val tracks = data?.get("tracks") as? JsonArray ?: return emptyList<NeteaseTrack>() to ""
        val list = ArrayList<NeteaseTrack>(tracks.size())
        val key = StringBuilder()
        for (element in tracks) {
            if (!element.isJsonObject) continue
            val track = try {
                PartyTrackCodec.read(element.asJsonObject)
            } catch (_: Exception) {
                null
            } ?: continue
            list.add(track)
            key.append(track.key).append('|')
        }
        return list to key.toString()
    }

    private fun heartbeat() {
        lastHeartbeat = System.currentTimeMillis()
        val c = code
        if (c.isEmpty()) return
        val net = transport
        net.patch("rooms/$c/members/${net.uid}", JsonObject().apply { add("lastSeen", sv()) })
    }

    // ---------------------------------------------------------------- 房主：把自己的播放状态发出去

    private fun hostTick() {
        pendingReq?.let {
            pendingReq = null
            runRequest(it)
        }
        // 播放列表的变化（换歌单、删歌、点下一首）单独发一次，成员据此看到完整的「正在播放」
        publishQueue()
        val cur = MusicPlayer.current ?: return
        if (!supportsSource(cur.source)) return
        val st = MusicPlayer.state
        val now = System.currentTimeMillis()
        if (st == MusicPlayer.State.LOADING || st == MusicPlayer.State.ERROR) {
            // 切歌了先告诉大家开始加载这首（暂停状态），真正开始放时再发一次
            if (cur.key != sentKey) send(cur, 0L, false)
            return
        }
        val playing = st == MusicPlayer.State.PLAYING
        val pos = MusicPlayer.positionMs
        val predicted = sentPos + if (sentPlaying) now - sentAt else 0L
        val changed = cur.key != sentKey || playing != sentPlaying || abs(pos - predicted) > 1500 || now - sentAt > STATE_REFRESH_MS
        if (changed) send(cur, pos, playing)
    }

    /** 房主：把整个播放列表写到房间。列表没换就什么都不做（加入晚的人靠订阅时的全量拿到） */
    private fun publishQueue() {
        val q = MusicPlayer.queue
        val index = MusicPlayer.index
        if (q.isEmpty()) return
        if (q === publishedQueue && index == publishedIndex) return
        // 上一次没发出去（服务器超时）：过一会儿再试，别每个 tick 都卡 5 秒
        val now = System.currentTimeMillis()
        if (now < queueRetryAt) return
        val tracks = JsonArray()
        var bytes = 0
        var count = 0
        for (t in q) {
            if (count >= MAX_QUEUE) break
            // 按字段长度估一下，别让一次请求超过服务器 256KB 的上限
            val cost = 80 + t.name.length + t.artists.sumOf { it.length } + t.coverUrl.length
            if (bytes + cost > MAX_QUEUE_BYTES) break
            tracks.add(PartyTrackCodec.write(t))
            bytes += cost
            count++
        }
        val payload = JsonObject().apply {
            addProperty("index", index)
            add("tracks", tracks)
        }
        if (transport.put("rooms/$code/queue", payload) != null) {
            publishedQueue = q
            publishedIndex = index
        } else {
            queueRetryAt = now + 10_000L
        }
    }

    /** 房主执行成员的请求（不用确认），执行完 [hostTick] 会照常把新状态发出去 */
    private fun runRequest(r: PartyRequest) {
        DangoLogger.info("Listen Together: member request {}", r.op)
        when (r.op) {
            PartyRequest.PLAY -> {
                val list = r.tracks
                if (list != null && list.isNotEmpty()) {
                    // 「播放全部」：整列替换房主的队列（房主不是成员，不会递归发请求）
                    MusicPlayer.playQueue(list, 0)
                } else {
                    r.track?.let { if (supportsSource(it.source)) MusicPlayer.playNext(it) }
                }
            }
            PartyRequest.NEXT -> MusicPlayer.next()
            PartyRequest.PREV -> MusicPlayer.prev()
            PartyRequest.SEEK, PartyRequest.PAUSE, PartyRequest.RESUME -> {
                val st = MusicPlayer.state
                if (st != MusicPlayer.State.PLAYING && st != MusicPlayer.State.PAUSED) return
                val play = if (r.op == PartyRequest.SEEK) st == MusicPlayer.State.PLAYING else r.op == PartyRequest.RESUME
                // 请求在路上花的时间补上：成员那边一直在放的话，现在应该已经放到后面了
                val travel = if (r.op != PartyRequest.PAUSE && play) (transport.serverNow() - r.at).coerceIn(0L, 5000L) else 0L
                val target = r.pos + travel
                val here = MusicPlayer.positionMs
                if (r.op != PartyRequest.SEEK && abs(here - target) < 1500) {
                    if ((st == MusicPlayer.State.PLAYING) != play) MusicPlayer.togglePause()
                } else {
                    MusicPlayer.syncTo(target, play)
                }
            }
        }
        // 马上把新状态发出去，不等进度差够大
        sentAt = 0L
        sentKey = ""
    }

    private fun send(t: NeteaseTrack, pos: Long, playing: Boolean) {
        val now = System.currentTimeMillis()
        sentKey = t.key
        sentPlaying = playing
        sentPos = pos
        sentAt = now
        val o = PartyTrackCodec.write(t).apply {
            addProperty("pos", pos)
            addProperty("playing", playing)
            addProperty("at", transport.serverNow())
        }
        transport.put("rooms/$code/state", o)
    }

    // ---------------------------------------------------------------- 成员：跟着房主放

    private fun expectedPos(s: RemoteState): Long =
        s.pos + if (s.playing) (transport.serverNow() - s.at).coerceAtLeast(0L) else 0L

    private fun followTick() {
        val s = remote ?: return
        val cur = MusicPlayer.current
        val broken = MusicPlayer.state == MusicPlayer.State.ERROR && requestedKey != s.track.key
        if (cur?.key != s.track.key || broken) {
            if (requestedKey != s.track.key) {
                requestedKey = s.track.key
                appliedQueueKey = s.queueKey
                MusicPlayer.playTrackAt(s.track, { expectedPos(s) }, !s.playing, s.queue)
                lastCorrection = System.currentTimeMillis()
            }
            return
        }
        // 房主换了歌单（加歌 / 删歌 / 换了列表）：把本地队列对齐，不影响正在放的这首
        if (s.queue.isNotEmpty() && s.queueKey != appliedQueueKey) {
            appliedQueueKey = s.queueKey
            MusicPlayer.applyPartyQueue(s.queue, s.track.key)
        }
        val st = MusicPlayer.state
        if (st == MusicPlayer.State.LOADING || st == MusicPlayer.State.ERROR) return
        val now = System.currentTimeMillis()
        if (now - lastCorrection < 2000) return

        val exp = expectedPos(s)
        val localPlaying = st == MusicPlayer.State.PLAYING
        val drift = abs(MusicPlayer.positionMs - exp)
        if (s.playing != localPlaying || (s.playing && drift > 700) || (!s.playing && drift > 1500)) {
            MusicPlayer.syncTo(exp, s.playing)
            lastCorrection = now
        }
    }
}
