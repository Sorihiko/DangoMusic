package me.sorihiko.dango.playback.salt

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SaltLyricsMappingTest {
    private val session = SaltSessionSource()

    private fun lyrics(json: String) = session.mapLyrics(
        SaltApi.parseLyrics(JsonParser().parse(json).asJsonObject),
    )

    @Test fun wordLyricsKeepRealWordTimes() {
        val result = lyrics("""{"lines":[{
            "startMs":100,"endMs":400,"text":"测试","timesValid":true,
            "cells":[{"startMs":100,"endMs":200,"text":"测"},{"startMs":200,"endMs":400,"text":"试"}]
        }]}""")
        val line = result.lines.single()
        assertEquals(100L, line.timeMs)
        assertEquals(400L, line.endMs)
        assertEquals(listOf(100L, 200L), line.words!!.map { it.startMs })
        assertEquals(listOf(100L, 200L), line.words!!.map { it.durMs })
        assertEquals("测试", line.words!!.joinToString("") { it.text })
    }

    @Test fun lineLyricsKeepEndTimeWithoutInventingWords() {
        val result = lyrics("""{"lines":[{"startMs":100,"endMs":400,"text":"测试","cells":[],"timesValid":true}]}""")
        assertEquals(400L, result.lines.single().endMs)
        assertNull(result.lines.single().words)
        assertTrue(result.hasSyncableTimes)
    }

    @Test fun invalidTimingDropsCellsAndDisablesSync() {
        val result = lyrics("""{"lines":[{
            "startMs":400,"endMs":100,"text":"测试","timesValid":false,
            "cells":[{"startMs":400,"endMs":100,"text":"测试"}]
        }]}""")
        assertNull(result.lines.single().words)
        assertFalse(result.hasSyncableTimes)
        assertEquals(-1, result.indexAt(500))
    }

    @Test fun indexAtSkipsAnInvalidLineBetweenValidLines() {
        val result = lyrics("""{"lines":[
            {"startMs":100,"endMs":200,"text":"有效一","timesValid":true},
            {"startMs":120,"endMs":50,"text":"无效","timesValid":false},
            {"startMs":300,"endMs":400,"text":"有效二","timesValid":true}
        ]}""")
        assertEquals(-1, result.indexAt(50))
        assertEquals(0, result.indexAt(150))
        assertEquals(2, result.indexAt(350))
    }
}
