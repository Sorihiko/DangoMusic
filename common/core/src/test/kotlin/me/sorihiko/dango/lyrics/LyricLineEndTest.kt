package me.sorihiko.dango.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineEndTest {

    @Test
    fun lyricsIndexStillWorksWithEndMs() {
        val lines = listOf(
            LyricLine(22000, "风从山谷的另一端吹来", null, 27500),
            LyricLine(28500, "云在晴空里慢慢排成行", null, 34000),
        )
        val lyrics = Lyrics(lines, "test")
        assertEquals(0, lyrics.indexAt(23000))
        assertEquals(1, lyrics.indexAt(30000))
        assertEquals(-1, lyrics.indexAt(100))
    }

    @Test
    fun splitUsesRealEndMsAsBoundary() {
        // 无逐字时间的行：估时以真实 endMs 为界，任何拆出的段都不得越过行结束
        val line = LyricLine(0, "今はまだ知らない 出会ってすらいない", null, 4000)
        val out = LyricSplit.split(listOf(line))
        assertTrue(out.size >= 2)
        for (seg in out) {
            assertTrue("segment starts at ${seg.timeMs}", seg.timeMs in 0..4000)
        }
    }

    @Test
    fun splitWithoutEndMsKeepsOldEstimate() {
        // 旧行为不变：没有 endMs 时按字数估时拆行
        val line = LyricLine(0, "今はまだ知らない 出会ってすらいない", null, null)
        val out = LyricSplit.split(listOf(line))
        assertTrue(out.size >= 2)
    }

    @Test
    fun wordTimesUnaffectedByEndMs() {
        val words = listOf(LyricWord(0, 500, "风从"), LyricWord(500, 500, "山谷的"))
        val line = LyricLine(0, "风从山谷的", words, 3000)
        val out = LyricSplit.split(listOf(line))
        // 无空格可拆时整行原样保留，逐字时间不被改写
        assertEquals(1, out.size)
        assertEquals(0L, out[0].timeMs)
        assertEquals(3000L, out[0].endMs)
        assertEquals(words, out[0].words)
    }
}
