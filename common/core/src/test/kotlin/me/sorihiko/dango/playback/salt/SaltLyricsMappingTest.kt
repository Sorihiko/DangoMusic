package me.sorihiko.dango.playback.salt

import com.google.gson.JsonParser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 歌词映射：协议样本（test/resources/saltbridge/）→ 内置歌词模型 */
class SaltLyricsMappingTest {

    private val session = SaltSessionSource()

    private fun fixture(name: String): String =
        String(javaClass.getResourceAsStream("/saltbridge/$name")!!.readBytes(), Charsets.UTF_8)

    private fun lyricsBody(name: String): SaltApi.LyricsBody =
        SaltApi.parseLyrics(JsonParser().parse(fixture(name)).asJsonObject)

    @Test
    fun wordLyricsMapToRealWordTimes() {
        val lyrics = session.mapLyrics(lyricsBody("lyrics.word.json"))
        assertEquals(3, lyrics.lines.size)
        val first = lyrics.lines[0]
        assertEquals(22_000L, first.timeMs)
        assertEquals(27_500L, first.endMs)
        assertEquals("风从山谷的另一端吹来", first.text)
        assertTrue(first.timesValid)
        val words = first.words
        assertNotNull(words)
        assertEquals(4, words!!.size)
        assertEquals(22_000L, words[0].startMs)
        assertEquals(1_200L, words[0].durMs)        // endMs - startMs
        assertEquals("风从", words[0].text)
        // 逐字时间不能被拆行改写
        assertEquals(23_200L, words[1].startMs)
    }

    @Test
    fun lineLyricsHaveNoWordsButRealEndMs() {
        val lyrics = session.mapLyrics(lyricsBody("lyrics.line.json"))
        assertEquals(3, lyrics.lines.size)
        val first = lyrics.lines[0]
        assertNull(first.words)                      // cells 空 → 退回逐行
        assertEquals(22_000L, first.timeMs)
        assertEquals(27_500L, first.endMs)           // 真实行结束，不靠下一行推断
        assertTrue(lyrics.hasSyncableTimes)
    }

    @Test
    fun timesInvalidLinesDropCellsAndSync() {
        val lyrics = session.mapLyrics(lyricsBody("lyrics.times-invalid.json"))
        assertEquals(2, lyrics.lines.size)
        // timesValid=false：cells 不可信 → 退回逐行
        assertNull(lyrics.lines[0].words)
        assertNull(lyrics.lines[1].words)
        assertFalse(lyrics.lines[0].timesValid)
        assertFalse(lyrics.lines[1].timesValid)
        // 整首无可信行时间 → 不可同步
        assertFalse(lyrics.hasSyncableTimes)
        assertEquals(-1, lyrics.indexAt(25_000L))
    }

    @Test
    fun indexAtSkipsInvalidLines() {
        val lyrics = session.mapLyrics(lyricsBody("lyrics.word.json"))
        assertEquals(-1, lyrics.indexAt(100))
        assertEquals(0, lyrics.indexAt(22_500L))
        assertEquals(2, lyrics.indexAt(36_000L))
    }

    @Test
    fun sampleStatusParses() {
        val root = JsonParser().parse(fixture("status.playing.json")).asJsonObject
        assertEquals("inst-sample-0001", root.get("instanceId").asString)
        assertEquals("playing", root.getAsJsonObject("playback").get("state").asString)
        assertEquals(23_000L, root.getAsJsonObject("playback").getAsJsonObject("position").get("reportedMs").asLong)
    }
}
