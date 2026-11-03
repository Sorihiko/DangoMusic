package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** 仅封装网易云客户端请求协议；不处理或解密歌曲文件。 */
internal object NeteasePlayerRequest {
    private const val PATH = "/api/song/enhance/player/url/v1"
    internal fun encode(json: String): String = encodePath(PATH, json)

    private fun encodePath(path: String, json: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(("nobody" + path + "use" + json + "md5forencrypt").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val plain = path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(Charsets.US_ASCII), "AES"))
        return cipher.doFinal(plain.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it.toInt() and 255) }
    }
    fun post(body: JsonObject, cookies: String): JsonObject? = postPath(PATH, body, cookies)

    fun cloudLyric(body: JsonObject, cookies: String): JsonObject? = postPath("/api/cloud/lyric/get", body, cookies)

    private fun postPath(path: String, body: JsonObject, cookies: String): JsonObject? {
        val conn = URL("https://interface.music.163.com/eapi" + path.removePrefix("/api")).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000; conn.readTimeout = 15000
            conn.instanceFollowRedirects = false; conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Cookie", cookies)
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Referer", "https://music.163.com/")
            // EAPI 的会话还需放进加密请求头；只从现有本地登录读取，不创建或共享身份。
            val payload = JsonParser().parse(body.toString()).asJsonObject
            val header = JsonObject().apply {
                addProperty("os", "pc"); addProperty("appver", "2.10.13")
                addProperty("requestId", System.currentTimeMillis().toString() + "_" + java.util.concurrent.ThreadLocalRandom.current().nextInt(1000))
                for (part in cookies.split(';')) {
                    val key = part.substringBefore('=').trim()
                    if (key in setOf("MUSIC_U", "MUSIC_A", "__csrf")) addProperty(key, part.substringAfter('=', "").trim())
                }
            }
            payload.add("header", header)
            conn.outputStream.use { it.write(("params=" + URLEncoder.encode(encodePath(path, payload.toString()), "UTF-8")).toByteArray(Charsets.US_ASCII)) }
            if (conn.responseCode !in 200..299) return JsonObject().apply { addProperty("code", conn.responseCode) }
            val text = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 1024 * 1024) { "NetEase response too large" }
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), Charsets.UTF_8)
            }
            return JsonParser().parse(text).takeIf { it.isJsonObject }?.asJsonObject
        } finally { conn.disconnect() }
    }
}
