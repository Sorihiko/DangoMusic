package me.sorihiko.dango.party

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.music.MusicSource

import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 一起听歌（通过 [PartyTransport] 同步，默认是 Firebase 实时数据库）：
 *   房主：开房间 → 得到房间码；自己正常放歌，切歌 / 拖进度 / 暂停时把状态写到数据库
 *   成员：输入房间码加入 → 监听房间，自动放同一首歌、跳到同一位置、跟着暂停
 *   成员控制：房主开着"允许成员控制"时，成员切歌 / 拖进度 / 暂停会写一条请求（[PartyRequest]），
 *     房主的游戏自动执行（不用房主确认），再照常把新状态发给所有人。播放状态始终只有房主在写。
 * 每个人按歌曲音源从自己的账号取歌，只同步歌曲信息、进度和播放状态。
 *
 * 数据结构：rooms/{房间码} = { host, hostName, memberControl, members/{uid} = {name, lastSeen}, state = {...}, req = {...} }
 * 旧版房主开的房间没有 memberControl，成员只能跟着放；旧版成员不认识 req，照样能加入、跟着放。
 * 每人只开一条监听，推下来的增量直接合并进本地 [RoomMirror]，平时不再读数据库。
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

    enum class Role { NONE, HOST, MEMBER }

    class Member(val uid: String, val name: String, val lastSeen: Long, val failedTrack: String = "")

    private class RemoteState(
        val track: NeteaseTrack,
        val pos: Long, val playing: Boolean, val at: Long,
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

    /** 同步层：开房 / 加入时选好（当前服务器能用就用服务器，否则 Firebase），在房间里不再换 */
    @Volatile var transport: PartyTransport = Firebase

    private val probing = AtomicBoolean(false)

    /** 界面：后台看看当前服务器能不能直接一起听（结果在 [ServerTransport.connected]） */
    fun probeServer() {
        if (role != Role.NONE || !probing.compareAndSet(false, true)) return
        worker.execute {
            try { ServerTransport.ready() } finally { probing.set(false) }
        }
    }

    /** 开房用哪个：当前服务器（房主的游戏）装了这个 mod 就用它，不用 Firebase */
    private fun pickForCreate(): PartyTransport = if (ServerTransport.ready()) ServerTransport else Firebase

    /** 离开了服务器：经这个服务器开的房间已经没了，本地退出 */
    fun serverLinkLost() {
        worker.execute {
            if (transport !== ServerTransport) return@execute
            if (role != Role.NONE) {
                abandonLocally()
                status = "You left the server, so the Listen Together room has closed."
            }
            transport = Firebase
        }
    }

    @Volatile private var remote: RemoteState? = null
    private var stopListen: (() -> Unit)? = null
    private val mirror = RoomMirror()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-Party").apply { isDaemon = true } }
    private val refreshPending = AtomicBoolean(false)
    private val rng = SecureRandom()

    // 房主：上一次发出去的状态
    private var sentKey = ""
    @Volatile private var roomProtocol = 1
    private var sentPlaying = false
    private var sentPos = 0L
    private var sentAt = 0L
    // 成员：已经请求加载的歌、上次纠正的时间
    @Volatile private var requestedKey = ""
    @Volatile private var lastCorrection = 0L
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
                    reportPlayback()
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
        if (MusicPlayer.current?.let { me.sorihiko.dango.music.CloudDrives.privateTrack(it) } == true) {
            status = "Switch away from the private Kugou cloud track before creating a room."
            return
        }
        busy = true
        status = "正在创建房间…"
        worker.execute {
            try {
                val net = pickForCreate()
                transport = net
                if (!net.configured) return@execute fail("还没配置 Firebase（firebase.properties）")
                if (!net.ensureAuth()) return@execute fail(connectError(net, "连不上 Firebase（" + net.lastStatus + "）"))
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
                // 先在当前服务器上找（局域网联机时房间多半开在这里），没有再找 Firebase
                val tries = listOfNotNull(ServerTransport.takeIf { it.ready() }, Firebase.takeIf { it.configured })
                if (tries.isEmpty()) return@execute fail("还没配置 Firebase（firebase.properties）")
                var found: Pair<PartyTransport, JsonElement>? = null
                var error = ""
                for (candidate in tries) {
                    if (!candidate.ensureAuth()) {
                        error = connectError(candidate, "连不上 Firebase（" + candidate.lastStatus + "）")
                        continue
                    }
                    candidate.syncClock()
                    val got = candidate.get("rooms/$c")
                    if (got == null) {
                        // 读失败（null）和房间不存在（读到空值）分开说
                        error = connectError(candidate, "加入失败（" + candidate.lastStatus + "）")
                        continue
                    }
                    if (!got.isJsonObject) {
                        if (error.isEmpty()) error = "房间 $c 不存在"
                        continue
                    }
                    found = candidate to got
                    break
                }
                val (net, room) = found ?: return@execute fail(error.ifEmpty { "房间 $c 不存在" })
                transport = net
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
        MusicPlayer.followMode = false
        reportedRoom = ""
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
    fun requestPlay(track: NeteaseTrack) = requestPlayList(listOf(track), 0)

    /**
     * 成员在列表里点了一首（或者"播放全部"）：本地先开始加载这首，同时把列表交给房主，房主换成这个列表从这首开始放。
     * 列表太长时只带这首前后的一段（最多 [PartyRequest.MAX_TRACKS] 首）
     */
    fun requestPlayList(tracks: List<NeteaseTrack>, start: Int) {
        val track = tracks.getOrNull(start) ?: return
        if (!canControl || !supportsSource(track.source)) return
        if (me.sorihiko.dango.music.CloudDrives.privateTrack(track)) {
            status = "Private Kugou cloud tracks cannot be shared in Listen Together. Leave the room to play them."
            return
        }
        requestedKey = track.key
        MusicPlayer.playTrackAt(track, { 0L }, false)
        lastCorrection = System.currentTimeMillis() + 4000
        val from = (start - PartyRequest.MAX_TRACKS / 4).coerceIn(0, maxOf(0, tracks.size - PartyRequest.MAX_TRACKS))
        val list = tracks.subList(from, minOf(tracks.size, from + PartyRequest.MAX_TRACKS))
            .filter { supportsSource(it.source) && !me.sorihiko.dango.music.CloudDrives.privateTrack(it) }
        sendRequest(PartyRequest.PLAY, 0L, track, if (list.size > 1) list else emptyList())
    }

    fun requestNext() = sendRequest(PartyRequest.NEXT, 0L, null)

    fun requestPrev() = sendRequest(PartyRequest.PREV, MusicPlayer.positionMs, null)

    /** 拖进度 / 暂停 / 继续：本地已经先生效了，这里只是告诉房主，并在房主的新状态到之前先别把自己拉回去 */
    fun requestState(op: String, pos: Long) {
        lastCorrection = System.currentTimeMillis() + 2500
        sendRequest(op, pos, null)
    }

    private fun sendRequest(op: String, pos: Long, track: NeteaseTrack?, tracks: List<NeteaseTrack> = emptyList()) {
        if (!canControl) return
        val c = code
        worker.execute {
            val net = transport
            val r = PartyRequest(newCode(), net.uid, myName(), op, pos, 0L, track, tracks)
            var ok = net.put("rooms/$c/req", PartyRequest.write(r, sv())) != null
            if (!ok && tracks.isNotEmpty() && (net.lastStatus == 401 || net.lastStatus == 403) && code == c) {
                // 房间所在的 Firebase 还是旧的数据库规则（不认识整个列表）：退回只发这一首
                val single = PartyRequest(newCode(), net.uid, myName(), op, pos, 0L, track)
                ok = net.put("rooms/$c/req", PartyRequest.write(single, sv())) != null
            }
            if (!ok && code == c) {
                // 401 / 403：数据库规则里还没有 req 这一条
                status = if (net.lastStatus == 401 || net.lastStatus == 403) "成员控制被数据库拒绝了，请房间所在的 Firebase 更新数据库规则"
                    else connectError(net, "操作没发出去（" + net.lastStatus + "）")
                resync()
            }
        }
    }

    /** 连不上时告诉玩家卡在哪：登录服务、数据库还是代理；服务器拒绝等其他情况用 [fallback] */
    private fun connectError(net: PartyTransport, fallback: String): String = if (net === ServerTransport) {
        if (net.lastFailure == PartyTransport.Failure.REJECTED) "The server rejected this Listen Together action." else "Cannot reach Listen Together on this server. Please retry."
    } else when (net.lastFailure) {
        PartyTransport.Failure.AUTH_UNREACHABLE -> "连不上 Google 登录服务。国内网络通常需要开代理，可在“连接方式”里设置"
        PartyTransport.Failure.SERVER_UNREACHABLE -> "连不上 Firebase 数据库。国内网络通常需要开代理，可在“连接方式”里设置"
        PartyTransport.Failure.PROXY_UNREACHABLE -> "代理连不上。请确认代理软件已打开、端口正确，或在“连接方式”里换一种"
        PartyTransport.Failure.RELAY_UNREACHABLE -> "中转和直连 Firebase 都连不上。请检查网络，或在“连接方式”里开代理"
        else -> fallback
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
            if (now - seen > MEMBER_TIMEOUT_MS) null else Member(uid, o.get("name")?.asString ?: "?", seen, o.get("failedTrack")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
        }?.sortedBy { if (it.uid == hostUid) 0 else 1 } ?: emptyList()

        val st = room.get("state") as? JsonObject
        remote = if (st == null) null else try {
            val track = PartyTrackCodec.read(st)
            if (track == null || !supportsSource(track.source)) null else RemoteState(
                track = track,
                pos = st.get("pos")?.asLong ?: 0L,
                playing = st.get("playing")?.asBoolean ?: false,
                at = st.get("at")?.asLong ?: now,
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

    @Volatile private var reportedRoom = ""
    private var reportedFailure = ""
    private var lastReport = 0L

    /** 只同步歌曲引用和失败状态，不发送平台错误文本、账号或会员信息。 */
    private fun reportPlayback() {
        val c = code
        if (role == Role.NONE || c.isEmpty()) { reportedRoom = ""; return }
        if (reportedRoom != c) { reportedRoom = c; reportedFailure = ""; lastReport = 0L }
        val failure = if (MusicPlayer.state == MusicPlayer.State.ERROR) MusicPlayer.current?.key.orEmpty() else ""
        val now = System.currentTimeMillis()
        if (failure == reportedFailure || now - lastReport < 5000) return
        lastReport = now
        val net = transport
        val result = net.patch("rooms/$c/members/${net.uid}", JsonObject().apply { addProperty("failedTrack", failure) })
        if (result != null && code == c) reportedFailure = failure
        else lastReport = now + 55000 // 旧规则或网络故障时每分钟最多重试一次。
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
        val cur = MusicPlayer.current ?: return
        if (!supportsSource(cur.source)) return
        if (me.sorihiko.dango.music.CloudDrives.privateTrack(cur)) return
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

    /** 房主执行成员的请求（不用确认），执行完 [hostTick] 会照常把新状态发出去 */
    private fun runRequest(r: PartyRequest) {
        DangoLogger.info("Listen Together: member request {}", r.op)
        when (r.op) {
            PartyRequest.PLAY -> r.track?.let { track ->
                if (!supportsSource(track.source)) return@let
                // 成员带了整个列表：换成这个列表，从成员点的那首开始；没带（旧版本）就插在当前这首后面
                val list = r.tracks.filter { supportsSource(it.source) }
                val at = list.indexOfFirst { it.key == track.key }
                if (list.size > 1 && at >= 0) MusicPlayer.playQueue(list, at) else MusicPlayer.playNext(track)
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
        if (cur?.key != s.track.key || (MusicPlayer.state == MusicPlayer.State.ERROR && requestedKey != s.track.key)) {
            if (requestedKey != s.track.key) {
                requestedKey = s.track.key
                MusicPlayer.playTrackAt(s.track, { expectedPos(s) }, !s.playing)
                lastCorrection = System.currentTimeMillis()
            }
            return
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
