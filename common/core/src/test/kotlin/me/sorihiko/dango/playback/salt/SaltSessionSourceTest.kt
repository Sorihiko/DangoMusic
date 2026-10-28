package me.sorihiko.dango.playback.salt

import com.sun.net.httpserver.HttpServer
import me.sorihiko.dango.playback.LyricsStatus
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class SaltSessionSourceTest {
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("session did not reach expected state", check())
    }

    @Test fun sameTrackLyricsRefreshAndUnavailableClearsOldBody() {
        val version = AtomicLong(7)
        val unavailable = AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/") { exchange ->
            val path = exchange.requestURI.path.substringAfterLast('/')
            val json = when (path) {
                "health" -> """{"ok":true,"protocolVersion":"1.0"}"""
                "status" -> """{
                    "instanceId":"test","generation":1,"servedAtMs":100,"trackStatus":"available",
                    "track":{"title":"Song","durationMs":10000},
                    "playback":{"state":"playing","position":{"reportedMs":0,"reportedAtMs":100},
                                "animation":{"mode":"confirmed","maxMs":3000}},
                    "lyrics":{"version":${version.get()},"status":"${if (unavailable.get()) "unavailable" else "available"}"}
                }"""
                "lyrics" -> """{
                    "instanceId":"test","generation":1,"version":${version.get()},
                    "status":"available","association":"associated",
                    "lines":[{"startMs":0,"endMs":10000,"text":"version-${version.get()}","timesValid":true}]
                }"""
                else -> "{}"
            }.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, json.size.toLong())
            exchange.responseBody.use { it.write(json) }
        }
        server.start()
        val source = SaltSessionSource(SaltApi({ server.address.port }, { "test" }), token = { "test" })
        try {
            source.start()
            await { source.snapshot().track != null }
            source.requestLyrics()
            await { source.lyrics()?.lines?.firstOrNull()?.text == "version-7" }
            version.set(8)
            await { source.lyrics()?.lines?.firstOrNull()?.text == "version-8" }
            unavailable.set(true)
            await { source.snapshot().lyricsStatus == LyricsStatus.UNAVAILABLE }
            assertNull(source.lyrics())
        } finally {
            source.stop()
            server.stop(0)
        }
        assertNull(source.snapshot().track)
    }
}
