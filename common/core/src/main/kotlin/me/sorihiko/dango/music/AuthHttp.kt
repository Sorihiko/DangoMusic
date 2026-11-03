package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.CookieManager
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Login transport: isolated cookie jars, HTTPS only, explicit provider host allowlist. */
internal object AuthHttp {
    class Response(val code: Int, val body: ByteArray, val location: String?) {
        fun text() = String(body, Charsets.UTF_8)
        fun json(): JsonObject {
            try {
                val raw = text().trim()
                return JsonParser().parse(if (raw.startsWith("{")) raw else raw.substringAfter('(').substringBeforeLast(')')).asJsonObject
            } catch (_: Exception) { error("Invalid authentication response") }
        }
    }

    fun allowed(source: MusicSource, host: String?): Boolean = when (source) {
        MusicSource.QQ -> host in setOf("xui.ptlogin2.qq.com", "ssl.ptlogin2.qq.com", "graph.qq.com", "y.qq.com", "u.y.qq.com", "szu.y.qq.com", "shu.y.qq.com", "c.y.qq.com")
        MusicSource.KUGOU -> host in setOf("login-user.kugou.com", "loginservice.kugou.com", "wwwapi.kugou.com", "wwwapiretry.kugou.com", "www.kugou.com", "gateway.kugou.com", "kugouvip.kugou.com", "mcloudservice.kugou.com")
        else -> false
    }

    fun acceptCookies(source: MusicSource, uri: URI, cookies: CookieManager, headers: Map<String, List<String>>) {
        require(uri.scheme == "https" && allowed(source, uri.host))
        val base = if (source == MusicSource.QQ) "qq.com" else "kugou.com"
        headers.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().forEach { header ->
            val parsed = try { HttpCookie.parse(header) } catch (_: IllegalArgumentException) { emptyList<HttpCookie>() }
            parsed.forEach { cookie ->
                val domain = cookie.domain?.trimStart('.')?.lowercase(java.util.Locale.ROOT) ?: uri.host
                if ((domain == base || domain.endsWith(".$base")) && (uri.host == domain || uri.host.endsWith(".$domain"))) {
                    // QQ uses Domain=ptlogin2.qq.com. Java's RFC2965 policy rejects this
                    // modern domain-cookie form unless a leading dot is normalized first.
                    cookie.domain = if (cookie.domain != null) ".$domain" else domain
                    cookie.path = cookie.path ?: "/"
                    cookie.secure = true
                    cookie.version = 0
                    cookies.cookieStore.add(uri, cookie)
                }
            }
        }
    }

    @JvmOverloads
    fun request(source: MusicSource, url: String, cookies: CookieManager, body: String? = null,
                contentType: String = "application/x-www-form-urlencoded", headers: Map<String, String> = emptyMap()): Response =
        requestBytes(source, url, cookies, body?.toByteArray(Charsets.UTF_8), contentType, headers)

    /** Some official endpoints exchange encrypted binary payloads, not UTF-8 form text. */
    fun requestBytes(source: MusicSource, url: String, cookies: CookieManager, body: ByteArray?,
                     contentType: String, headers: Map<String, String> = emptyMap()): Response {
        val uri = URI(url)
        require(uri.scheme == "https" && allowed(source, uri.host) && uri.userInfo == null && (uri.port == -1 || uri.port == 443)) { "Invalid authentication endpoint" }
        val conn = uri.toURL().openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8000; conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val referer = when (uri.host) {
                "graph.qq.com" -> "https://graph.qq.com/"
                "ssl.ptlogin2.qq.com" -> "https://xui.ptlogin2.qq.com/"
                else -> if (source == MusicSource.QQ) "https://y.qq.com/" else "https://www.kugou.com/"
            }
            conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            cookies.get(uri, emptyMap()).forEach { (name, values) -> conn.setRequestProperty(name, values.joinToString("; ")) }
            if (body != null) {
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            acceptCookies(source, uri, cookies, conn.headerFields.filterKeys { it != null })
            val bytes = if (code in 200..299) conn.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 2 * 1024 * 1024) { "Authentication response too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } else ByteArray(0)
            return Response(code, bytes, conn.getHeaderField("Location")?.let { URL(uri.toURL(), it).toString() })
        } catch (_: Exception) {
            // Transport exceptions may contain the full URL, including a provider token.
            throw IllegalStateException("Authentication request failed")
        } finally { conn.disconnect() }
    }

    fun form(values: Map<String, String>): String = values.entries.joinToString("&") { "${SourceHttp.enc(it.key)}=${SourceHttp.enc(it.value)}" }
}
