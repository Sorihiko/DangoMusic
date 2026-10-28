package me.sorihiko.dango.playback.salt

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.lyrics.LyricWord
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.playback.LyricsStatus
import me.sorihiko.dango.playback.PlaybackSessionSource
import me.sorihiko.dango.playback.PlaybackSnapshot
import me.sorihiko.dango.playback.SessionLink
import me.sorihiko.dango.playback.SessionState
import me.sorihiko.dango.playback.SessionTrack

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** SaltBridge 会话：后台串行轮询，切换来源后忽略旧会话的响应。 */
class SaltSessionSource(
    private val api: SaltApi = SaltApi(),
    private val token: () -> String = { SaltConfig.token() },
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : PlaybackSessionSource {

    /** 连接状态：与播放状态分开建模 */
    enum class Connection {
            STOPPED,
        CONNECTING,
        READY,
        /** 没有令牌：提示用户配置 */
        NO_TOKEN,
        /** 令牌无效：提示重新配置 */
        AUTH_FAILED,
        /** 权限不足 */
        FORBIDDEN,
        /** SaltBridge 不在线（可自动恢复） */
        NOT_RUNNING,
        /** 协议版本不匹配 */
        PROTOCOL_MISMATCH,
    }

    @Volatile var connection: Connection = Connection.STOPPED
        private set

    /** 给界面看的补充说明（不含令牌） */
    @Volatile var connectionNote: String = ""
        private set

    private val epoch = AtomicLong(0)
    private val lyricsWanted = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    // 最近一次成功映射的快照（原子整体替换）
    @Volatile private var snap: PlaybackSnapshot =
        PlaybackSnapshot(SessionLink.DISCONNECTED, null, SessionState.UNKNOWN, 0L, LyricsStatus.UNKNOWN, 0L)

    private var instanceId: String = ""
    private var generation: Long = -1L
    private var trackDurationMs: Long = 0L

    @Volatile private var lyricsBody: Lyrics? = null
    @Volatile private var lyricsVersion: Long = -1L
    @Volatile private var lyricsFailedVersion: Long = -1L

    private val clock = SaltClock()

    @Synchronized
    fun start() {
        if (worker?.isAlive == true) return
        connection = Connection.CONNECTING
        connectionNote = ""
        val myEpoch = epoch.incrementAndGet()
        worker = Thread({ loop(myEpoch) }, "Dango-SaltPoll").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        epoch.incrementAndGet()
        worker?.interrupt()
        worker = null
        connection = Connection.STOPPED
        connectionNote = ""
        controlExec.queue.clear()
        resetData()
        publishDisconnected()
    }

    /** 配置（端口 / 令牌）变化后调用：重建连接 */
    @Synchronized
    fun restart() {
        stop()
        start()
    }

    private fun resetData() {
        instanceId = ""
        generation = -1L
        trackDurationMs = 0L
        lyricsBody = null
        lyricsVersion = -1L
        lyricsFailedVersion = -1L
        lyricsWanted.set(false)
        clock.reset()
    }

    private fun publishDisconnected() {
        snap = PlaybackSnapshot(SessionLink.DISCONNECTED, null, SessionState.UNKNOWN, 0L, LyricsStatus.UNKNOWN, 0L)
    }

    private fun loop(myEpoch: Long) {
        var backoffMs = 1000L
        while (epoch.get() == myEpoch && !Thread.currentThread().isInterrupted) {
            try {
                if (token().isEmpty()) {
                    connection = Connection.NO_TOKEN
                    connectionNote = "Set the access token from the SaltBridge settings"
                    publishDisconnected()
                    sleepCancellable(3000, myEpoch)
                    continue
                }
                val health = api.health()
                if (epoch.get() != myEpoch) return
                if (!health.ok || health.protocolVersion != "1.0") {
                    connection = Connection.PROTOCOL_MISMATCH
                    connectionNote = "protocolVersion=" + health.protocolVersion
                    publishDisconnected()
                    sleepCancellable(5000, myEpoch)
                    continue
                }
                synchronized(this) {
                    if (epoch.get() != myEpoch) return
                    connection = Connection.READY
                    connectionNote = ""
                }

                while (epoch.get() == myEpoch && !Thread.currentThread().isInterrupted) {
                    val t0 = monoMs()
                    val st = api.status()
                    synchronized(this) {
                        if (epoch.get() != myEpoch) return
                        applyStatus(st, monoMs())
                    }
                    backoffMs = 1000L
                    maybeFetchLyrics(myEpoch)
                    val used = monoMs() - t0
                    // 约 500ms 一轮；请求本身耗时扣掉，天然限频
                    sleepCancellable((500L - used).coerceAtLeast(50L), myEpoch)
                }
            } catch (e: Exception) {
                synchronized(this) {
                    if (epoch.get() != myEpoch) return
                    handlePollError(e)
                }
                sleepCancellable(backoffMs, myEpoch)
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
                continue
            }
            backoffMs = 1000L
        }
    }

    private fun handlePollError(e: Exception) {
        when (e) {
            is SaltApi.ApiException.Unauthorized -> onUnauthorized()
            is SaltApi.ApiException.Forbidden -> {
                connection = Connection.FORBIDDEN
                connectionNote = e.apiCode
                publishDisconnected()
            }
            is SaltApi.ApiException.TooManyRequests -> {
                connectionNote = "rate limited"
            }
            is java.net.SocketTimeoutException, is java.net.ConnectException -> {
                connection = Connection.NOT_RUNNING
                connectionNote = "SaltBridge not reachable"
                publishDisconnected()
            }
            else -> {
                connection = Connection.NOT_RUNNING
                connectionNote = e.javaClass.simpleName
                publishDisconnected()
            }
        }
    }

    /** 令牌被拒：丢弃缓存（可能已轮换），提示重新配置；下轮自动重新发现 */
    private fun onUnauthorized() {
        connection = Connection.AUTH_FAILED
        connectionNote = "Token rejected — retrying auto discover"
        SaltConfig.invalidate()
        publishDisconnected()
    }

    private fun sleepCancellable(ms: Long, myEpoch: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (epoch.get() != myEpoch) Thread.currentThread().interrupt()
    }

    private fun applyStatus(st: SaltApi.Status, receivedMono: Long) {
        if (st.instanceId != instanceId) {
            // 新启用周期：清掉全部旧缓存
            resetData()
            instanceId = st.instanceId
        }
        if (st.generation != generation) {
            // 代次变化：旧歌词作废
            generation = st.generation
            lyricsBody = null
            lyricsVersion = -1L
            lyricsFailedVersion = -1L
        }

        if (!st.hasTrack || st.lyricsStatus !in setOf("available", "stale")) {
            lyricsBody = null
            lyricsVersion = -1L
        }
        val track = if (st.hasTrack && st.trackStatus == "available") {
            trackDurationMs = st.durationMs
            SessionTrack(
                key = "salt:$instanceId:$generation",
                title = st.title,
                artists = if (st.artist.isBlank()) emptyList() else listOf(st.artist),
                durationMs = st.durationMs,
                coverBytes = run {
                    val inst = instanceId
                    val gen = generation
                    val sessionEpoch = epoch.get()
                    fun(): ByteArray? {
                        return if (epoch.get() != sessionEpoch) null
                        else api.cover(inst, gen).takeIf { epoch.get() == sessionEpoch }
                    }
                },
            )
        } else {
            trackDurationMs = 0L
            null
        }

        val state = when (st.state) {
            "playing" -> SessionState.PLAYING
            "paused" -> SessionState.PAUSED
            "buffering" -> SessionState.LOADING
            "ended" -> SessionState.ENDED
            "stopped" -> SessionState.NONE
            else -> SessionState.UNKNOWN
        }

        val position = clock.onStatus(
            instanceId = st.instanceId,
            generation = st.generation,
            reportedMs = st.reportedMs,
            reportedAtMs = st.reportedAtMs,
            servedAtMs = st.servedAtMs,
            canExtrapolate = st.animationMode != "disabled",
            maxMs = st.animationMaxMs,
            durationMs = trackDurationMs,
            localReceivedMonoMs = receivedMono,
            localNowMonoMs = monoMs(),
        ) ?: 0L

        snap = PlaybackSnapshot(
            link = SessionLink.READY,
            track = track,
            state = state,
            positionMs = position,
            lyricsStatus = lyricsStatusOf(st),
            lyricsVersion = st.lyricsVersion,
        )
    }

    private fun lyricsStatusOf(st: SaltApi.Status): LyricsStatus = when {
        st.lyricsStatus == "unavailable" -> LyricsStatus.UNAVAILABLE
        lyricsBody != null -> LyricsStatus.AVAILABLE       // available 或 stale：保留旧行作显示缓冲
        st.lyricsStatus == "available" || st.lyricsStatus == "stale" ->
            if (lyricsFailedVersion == st.lyricsVersion) LyricsStatus.UNAVAILABLE else LyricsStatus.LOADING
        else -> LyricsStatus.UNKNOWN
    }

    private fun maybeFetchLyrics(myEpoch: Long) {
        if (!lyricsWanted.get()) return
        val request = synchronized(this) {
            if (epoch.get() != myEpoch) return
            val st = snap
            if (st.track == null || st.lyricsStatus == LyricsStatus.UNAVAILABLE) return
            if (lyricsBody != null && lyricsVersion == st.lyricsVersion) return
            if (lyricsFailedVersion == st.lyricsVersion) return
            instanceId to generation
        }

        try {
            val body = api.lyrics(request.first, request.second)
            synchronized(this) {
                if (epoch.get() != myEpoch) return
                // 返回后复查身份，丢弃过期结果
                if (body.instanceId != instanceId || body.generation != generation) return
                // unconfirmed 的正文可能属于别的曲目，不发布
                if (body.association == "unconfirmed") {
                    lyricsWanted.set(true)
                    return
                }
                if (body.status == "unavailable" || body.lines.isEmpty()) {
                    lyricsBody = null
                    lyricsVersion = body.version
                    lyricsFailedVersion = body.version
                    return
                }
                lyricsBody = mapLyrics(body)
                lyricsVersion = body.version
                lyricsFailedVersion = -1L
                DangoLogger.info("Salt lyrics loaded: {} lines v{}", body.lines.size, body.version)
            }
        } catch (e: Exception) {
            synchronized(this) {
                if (epoch.get() != myEpoch) return
                when (e) {
                    is SaltApi.ApiException.Unauthorized -> onUnauthorized()
                    is SaltApi.ApiException.Conflict, is SaltApi.ApiException.NotFound -> {
                        // 代次已变 / 没有曲目：等下一轮状态再决定
                    }
                    else -> DangoLogger.warn("Salt lyrics fetch failed: {}", e.javaClass.simpleName)
                }
            }
        }
    }

    /** 歌词 JSON → 复用内置歌词模型（startMs→timeMs，cells→LyricWord，保留真实 endMs） */
    fun mapLyrics(body: SaltApi.LyricsBody): Lyrics {
        val lines = body.lines.map { l ->
            val words = if (l.timesValid && l.cells.isNotEmpty()) {
                l.cells.map { c ->
                    LyricWord(c.startMs, (c.endMs - c.startMs).coerceAtLeast(0L), c.text)
                }
            } else null
            LyricLine(l.startMs, l.text, words, l.endMs, l.timesValid)
        }
        return Lyrics(lines, "Salt Player")
    }

    @Synchronized
    override fun snapshot(): PlaybackSnapshot {
        val s = snap
        if (s.link == SessionLink.READY && s.track != null) {
            val pos = clock.displayed(monoMs()) ?: s.positionMs
            return PlaybackSnapshot(s.link, s.track, s.state, pos, s.lyricsStatus, s.lyricsVersion)
        }
        return s
    }

    override fun lyrics(): Lyrics? = if (snap.link == SessionLink.READY) lyricsBody else null

    override fun requestLyrics() {
        if (snap.track != null) lyricsWanted.set(true)
    }

    private val controlExec = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(32),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "Dango-SaltControl").apply { isDaemon = true } },
    )

    private fun newRequestId(): String = "dm-" + java.util.UUID.randomUUID().toString()

    /** 播放。202 只代表入队，最终状态以 status 轮询为准 */
    fun play() = send("play", null, false)

    fun pause() = send("pause", null, false)

    fun next() = send("next", null, false)

    fun previous() = send("previous", null, false)

    /** seek 必须带 expectedGeneration */
    fun seekTo(positionMs: Long) = send("seek", positionMs.coerceAtLeast(0L), true)

    /** 播放状态未知时不猜 toggle */
    fun togglePause() {
        when (snap.state) {
            SessionState.PLAYING -> pause()
            SessionState.PAUSED, SessionState.ENDED, SessionState.NONE -> play()
            else -> {}   // UNKNOWN / LOADING / ERROR：不猜
        }
    }

    @Synchronized
    private fun send(command: String, positionMs: Long?, needGeneration: Boolean) {
        val inst = instanceId
        val gen = generation
        if (inst.isEmpty() || connection != Connection.READY) return
        if (needGeneration && gen < 0) return
        val id = newRequestId()
        val myEpoch = epoch.get()
        try {
            controlExec.execute {
                if (epoch.get() != myEpoch) return@execute
                try {
                    api.control(
                        requestId = id,
                        expectedInstanceId = inst,
                        command = command,
                        positionMs = positionMs,
                        expectedGeneration = if (needGeneration) gen else null,
                    )
                } catch (e: Exception) {
                    if (e is SaltApi.ApiException.Unauthorized) synchronized(this) {
                        if (epoch.get() == myEpoch) onUnauthorized()
                    }
                    // 控制失败不重试：用户下一次点击就是新操作
                    DangoLogger.warn("Salt control {} failed: {}", command, e.javaClass.simpleName)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            DangoLogger.warn("Salt control queue is full")
        }
    }
}
