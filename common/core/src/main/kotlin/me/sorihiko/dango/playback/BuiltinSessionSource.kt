package me.sorihiko.dango.playback

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicSources
import me.sorihiko.dango.music.NeteaseTrack

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 内置播放器读取面：生产环境读 [MusicPlayer]，测试可注入假实现 */
class BuiltinPlayerPort(
    val current: () -> NeteaseTrack? = { MusicPlayer.current },
    val state: () -> MusicPlayer.State = { MusicPlayer.state },
    val positionMs: () -> Long = { MusicPlayer.positionMs },
)

/**
 * 内置播放适配器：从 [MusicPlayer] 读取当前播放会话，不改变其任何行为。
 *
 * 歌词按曲目 key 缓存：加载成功或确认无歌词（loader 返回 null）后不再重复请求；
 * 加载异常不缓存结论，允许下次重试。歌词版本恒为 1（同一首歌的正文不会变）。
 */
class BuiltinSessionSource(
    private val player: BuiltinPlayerPort = BuiltinPlayerPort(),
    private val lyricsLoader: (NeteaseTrack) -> Lyrics? = { track ->
        MusicSources.api(track.source).lyrics(track)
    },
    private val io: Executor = defaultExecutor(),
) : PlaybackSessionSource {

    private object NotFound

    private val cache = ConcurrentHashMap<String, Any>()          // key -> Lyrics | NotFound
    private val pending = ConcurrentHashMap<String, Long>()
    private var epoch = 0L

    override fun snapshot(): PlaybackSnapshot {
        val track = player.current()
            ?: return PlaybackSnapshot(SessionLink.READY, null, SessionState.NONE, 0L, LyricsStatus.UNKNOWN, 0L)
        return PlaybackSnapshot(
            link = SessionLink.READY,
            track = track.toSessionTrack(),
            state = mapState(player.state()),
            positionMs = player.positionMs(),
            lyricsStatus = lyricsStatus(track.key),
            lyricsVersion = 1L,
        )
    }

    override fun lyrics(): Lyrics? {
        val key = player.current()?.key ?: return null
        return cache[key] as? Lyrics
    }

    @Synchronized
    override fun requestLyrics() {
        val track = player.current() ?: return
        val key = track.key
        val requestEpoch = epoch
        if (cache.containsKey(key) || pending.putIfAbsent(key, requestEpoch) != null) return
        io.execute {
            try {
                val lyrics = lyricsLoader(track)
                // 只缓存确定结论；异常留给下次重试
                synchronized(this) {
                    if (epoch == requestEpoch) cache[key] = lyrics ?: NotFound
                }
            } catch (e: Exception) {
                DangoLogger.warn("Builtin lyrics load failed: {}", e.toString())
            } finally {
                pending.remove(key, requestEpoch)
            }
        }
    }

    /** 清空歌词缓存与在途请求（切换到 Salt 模式时使旧加载失效） */
    @Synchronized
    fun invalidateLyrics() {
        epoch++
        cache.clear()
        pending.clear()
    }

    private fun lyricsStatus(key: String): LyricsStatus = when {
        cache[key] is Lyrics -> LyricsStatus.AVAILABLE
        cache[key] === NotFound -> LyricsStatus.UNAVAILABLE
        pending.containsKey(key) -> LyricsStatus.LOADING
        else -> LyricsStatus.UNKNOWN
    }

    companion object {
        fun mapState(state: MusicPlayer.State): SessionState = when (state) {
            MusicPlayer.State.STOPPED -> SessionState.NONE
            MusicPlayer.State.LOADING -> SessionState.LOADING
            MusicPlayer.State.PLAYING -> SessionState.PLAYING
            MusicPlayer.State.PAUSED -> SessionState.PAUSED
            MusicPlayer.State.ERROR -> SessionState.ERROR
        }

        fun NeteaseTrack.toSessionTrack() = SessionTrack(
            key = key,
            title = name,
            artists = artists,
            durationMs = durationMs,
            coverUrl = coverUrl,
        )

        private fun defaultExecutor(): Executor =
            Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-SessionLyrics").apply { isDaemon = true } }
    }
}
