package me.sorihiko.dango.playback.salt

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import me.sorihiko.dango.playback.LyricsStatus
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class SaltSessionSourceTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/saltbridge/$name")!!.bufferedReader().use { it.readText() }

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
                "health" -> fixture("health.json")
                "capabilities" -> fixture("capabilities.json")
                "status" -> JsonParser().parse(fixture("status.playing.json")).asJsonObject.apply {
                    getAsJsonObject("lyrics").addProperty("version", version.get())
                    getAsJsonObject("lyrics").addProperty("status", if (unavailable.get()) "unavailable" else "available")
                }.toString()
                "lyrics" -> JsonParser().parse(fixture("lyrics.line.json")).asJsonObject.apply {
                    addProperty("version", version.get())
                    getAsJsonArray("lines")[0].asJsonObject.addProperty("text", "version-${version.get()}")
                }.toString()
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
