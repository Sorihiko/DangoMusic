package me.sorihiko.dango.playback.salt

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class SaltCoverTest {
    private fun withServer(block: (HttpServer, SaltApi) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            block(server, SaltApi({ server.address.port }, { "test-token" }))
        } finally {
            server.stop(0)
        }
    }

    @Test fun authenticatedCoverRetriesPendingAndReturnsBytes() = withServer { server, api ->
        val calls = AtomicInteger()
        val image = byteArrayOf(1, 2, 3, 4)
        server.createContext("/api/v1/cover") { exchange ->
            assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
            assertTrue(exchange.requestURI.rawQuery.contains("expectedInstanceId=instance-1"))
            assertTrue(exchange.requestURI.rawQuery.contains("expectedGeneration=7"))
            val pending = calls.incrementAndGet() == 1
            val body = if (pending) "{\"status\":\"pending\",\"retryAfterMs\":50}".toByteArray() else image
            exchange.responseHeaders.set("Content-Type", if (pending) "application/json" else "image/png")
            exchange.responseHeaders.set("X-SaltBridge-Instance-Id", "instance-1")
            exchange.responseHeaders.set("X-SaltBridge-Generation", "7")
            exchange.sendResponseHeaders(if (pending) 202 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        assertArrayEquals(image, api.cover("instance-1", 7))
        assertEquals(2, calls.get())
    }

    @Test fun rejectsCoverFromAnotherGeneration() = withServer { server, api ->
        server.createContext("/api/v1/cover") { exchange ->
            exchange.responseHeaders.set("Content-Type", "image/png")
            exchange.responseHeaders.set("X-SaltBridge-Instance-Id", "instance-1")
            exchange.responseHeaders.set("X-SaltBridge-Generation", "8")
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.use { it.write(1) }
        }
        assertNull(api.cover("instance-1", 7))
    }

    @Test fun unavailableCoverReturnsPlaceholder() = withServer { server, api ->
        server.createContext("/api/v1/cover") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        assertNull(api.cover("instance-1", 7))
    }
}
