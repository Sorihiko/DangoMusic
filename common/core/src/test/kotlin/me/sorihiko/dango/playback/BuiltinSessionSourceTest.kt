package me.sorihiko.dango.playback

import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.NeteaseTrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import java.util.ArrayDeque
import java.util.concurrent.Executor

class BuiltinSessionSourceTest {

    private val track = NeteaseTrack(1L, "示例曲目 · 晴日", listOf("测试歌手"), 225000L, "")
    private val lyrics = Lyrics(listOf(LyricLine(0, "风从山谷的另一端吹来")), "test")

    /** 同步执行器：测试里直接观察结果 */
    private val direct = Executor { it.run() }

    /** 手动执行器：观察在途请求的幂等性 */
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.poll().run() }
    }

    private fun source(
        current: NeteaseTrack? = track,
        state: MusicPlayer.State = MusicPlayer.State.PLAYING,
        position: Long = 23000L,
        loader: (NeteaseTrack) -> Lyrics? = { lyrics },
        io: Executor = direct,
    ) = BuiltinSessionSource(
        player = BuiltinPlayerPort(current = { current }, state = { state }, positionMs = { position }),
        lyricsLoader = loader,
        io = io,
    )

    // ---------------------------------------------------------------- 快照映射

    @Test
    fun snapshotWithoutTrackIsNone() {
        val snap = source(current = null).snapshot()
        assertNull(snap.track)
        assertEquals(SessionState.NONE, snap.state)
        assertEquals(SessionLink.READY, snap.link)
        assertEquals(LyricsStatus.UNKNOWN, snap.lyricsStatus)
    }

    @Test
    fun snapshotMapsTrackAndPosition() {
        val snap = source().snapshot()
        assertEquals("netease:1", snap.track?.key)
        assertEquals("示例曲目 · 晴日", snap.track?.title)
        assertEquals(listOf("测试歌手"), snap.track?.artists)
        assertEquals(225000L, snap.track?.durationMs)
        assertEquals(SessionState.PLAYING, snap.state)
        assertEquals(23000L, snap.positionMs)
        assertTrue(snap.playing)
        assertEquals(1L, snap.lyricsVersion)
    }

    @Test
    fun mapStateCoversAllStates() {
        assertEquals(SessionState.NONE, BuiltinSessionSource.mapState(MusicPlayer.State.STOPPED))
        assertEquals(SessionState.LOADING, BuiltinSessionSource.mapState(MusicPlayer.State.LOADING))
        assertEquals(SessionState.PLAYING, BuiltinSessionSource.mapState(MusicPlayer.State.PLAYING))
        assertEquals(SessionState.PAUSED, BuiltinSessionSource.mapState(MusicPlayer.State.PAUSED))
        assertEquals(SessionState.ERROR, BuiltinSessionSource.mapState(MusicPlayer.State.ERROR))
    }

    // ---------------------------------------------------------------- 歌词缓存

    @Test
    fun lyricsLoadBecomesAvailable() {
        val s = source()
        assertEquals(LyricsStatus.UNKNOWN, s.snapshot().lyricsStatus)
        s.requestLyrics()
        assertEquals(LyricsStatus.AVAILABLE, s.snapshot().lyricsStatus)
        assertNotNull(s.lyrics())
    }

    @Test
    fun nullLyricsCachedAsUnavailable() {
        val s = source(loader = { null })
        s.requestLyrics()
        assertEquals(LyricsStatus.UNAVAILABLE, s.snapshot().lyricsStatus)
        assertNull(s.lyrics())
    }

    @Test
    fun loaderFailureIsNotCachedAndCanRetry() {
        var calls = 0
        val s = source(loader = {
            calls++
            if (calls == 1) throw IllegalStateException("网络失败") else lyrics
        })
        s.requestLyrics()
        assertEquals(LyricsStatus.UNKNOWN, s.snapshot().lyricsStatus)  // 异常不缓存结论
        s.requestLyrics()
        assertEquals(LyricsStatus.AVAILABLE, s.snapshot().lyricsStatus)
        assertEquals(2, calls)
    }

    @Test
    fun requestIsIdempotentWhilePending() {
        val manual = ManualExecutor()
        val s = source(io = manual)
        s.requestLyrics()
        s.requestLyrics()
        s.requestLyrics()
        assertEquals(1, manual.tasks.size)
        assertEquals(LyricsStatus.LOADING, s.snapshot().lyricsStatus)
        manual.drain()
        assertEquals(LyricsStatus.AVAILABLE, s.snapshot().lyricsStatus)
    }

    @Test
    fun invalidateClearsCacheAndPending() {
        val s = source()
        s.requestLyrics()
        assertEquals(LyricsStatus.AVAILABLE, s.snapshot().lyricsStatus)
        s.invalidateLyrics()
        assertEquals(LyricsStatus.UNKNOWN, s.snapshot().lyricsStatus)

        val manual = ManualExecutor()
        val s2 = source(io = manual)
        s2.requestLyrics()
        s2.invalidateLyrics()
        s2.requestLyrics()                    // 旧 pending 已清，允许再次请求
        assertEquals(2, manual.tasks.size)
    }

    @Test
    fun trackKeyChangeSwitchesLyrics() {
        val other = NeteaseTrack(2L, "示例曲目 · 雨天", listOf("测试歌手"), 180000L, "")
        var current: NeteaseTrack? = track
        val s = BuiltinSessionSource(
            player = BuiltinPlayerPort(current = { current }, state = { MusicPlayer.State.PLAYING }, positionMs = { 0L }),
            lyricsLoader = { if (it.id == 1L) lyrics else null },
            io = direct,
        )
        s.requestLyrics()
        assertEquals(LyricsStatus.AVAILABLE, s.snapshot().lyricsStatus)
        current = other
        assertEquals(LyricsStatus.UNKNOWN, s.snapshot().lyricsStatus)  // 新曲目需重新查询
        assertEquals("netease:2", s.snapshot().track?.key)
    }
    @Test
    fun invalidatedInflightLyricsCannotRestoreCacheOrClearNewPending() {
        val manual = ManualExecutor()
        val s = source(io = manual)
        s.requestLyrics()
        s.invalidateLyrics()
        s.requestLyrics()
        manual.tasks.poll().run()
        assertNull(s.lyrics())
        assertEquals(LyricsStatus.LOADING, s.snapshot().lyricsStatus)
        manual.drain()
        assertNotNull(s.lyrics())
    }

}
