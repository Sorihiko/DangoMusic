package me.sorihiko.dango.music

import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
import java.net.CookieManager
import java.security.MessageDigest
import java.security.SecureRandom
import java.math.BigInteger
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Web request protocol observed in Kugou's own public login/player scripts. */
internal object KugouWeb {
    private const val WEB_SIGN_KEY = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    private const val LOGIN_MODULUS = "B1B1EC76A1BBDBF0D18E8CD9A87E53FA3881E2F004C67C9DDA2CA677DBEFA3D61DF8463FE12D84FF4B4699E02C9D41CAB917F5A8FB9E35580C4BDF97763A0420A476295D763EE10174E6F9EBF7DF8A77BA5B20CDA4EE705DEF5BBA3C88567B9656E52C9CD5CD95CA735FF2D25F762B133273EEEB7B4F3EA8B6DA29040F3B67CD"

    fun signed(values: Map<String, String>, device: String): Map<String, String> {
        val params = linkedMapOf("appid" to "1014", "srcappid" to "2919", "clientver" to "20000",
            "clienttime" to System.currentTimeMillis().toString(), "mid" to device, "uuid" to device, "dfid" to "-")
        params.putAll(values)
        val material = WEB_SIGN_KEY + params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + WEB_SIGN_KEY
        params["signature"] = MessageDigest.getInstance("MD5").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it.toInt() and 255) }
        return params
    }

    fun get(path: String, params: Map<String, String>, device: String, cookies: CookieManager): JsonObject =
        AuthHttp.request(MusicSource.KUGOU, path + "?" + AuthHttp.form(signed(params, device)), cookies).json()

    fun songInfo(track: NeteaseTrack, account: NativeAccount, metadata: Boolean = true): JsonObject {
        val result = get("https://wwwapi.kugou.com/play/songinfo",
            mapOf("hash" to track.nativeId, "album_id" to (if (metadata) track.albumId else ""), "platid" to "4", "userid" to account.userId, "token" to account.token,
                "dfid" to NativeAccounts.cookie(account.cookies, "kg_dfid").ifEmpty { "-" }), account.deviceId, account.cookies)
        if (result.number("err_code") == 30020L) throw SourceAccessException("Kugou requires additional security verification. Please try again later.")
        return result
    }

    fun cookiePart(cookies: CookieManager, name: String): String {
        val raw = NativeAccounts.cookie(cookies, "KuGoo").split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=') ?: return ""
        return try { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { "" }
    }

    /** The scanned QR token is one-time: exchange it for the web playback session first. */
    fun tokenLogin(userId: String, qrToken: String, device: String, cookies: CookieManager): JsonObject {
        val now = System.currentTimeMillis()
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val random = SecureRandom()
        val key = (0 until 16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        val encrypted = encryptToken(qrToken, key, now)
        // 较新的酷狗账号拒绝 clientver=1000（error 34102 "当前版本太低"，#22），用和其他网页接口一样的 20000
        val params = mapOf("clientver" to "20000", "clienttime" to (now / 1000).toString(), "dev" to "web", "userid" to userId,
            "plat" to "4", "clienttime_ms" to now.toString(), "pk" to encrypted.first, "params" to encrypted.second)
        val url = "https://loginservice.kugou.com/v1/login_by_token_get?" + AuthHttp.form(signed(params, device))
        val response = AuthHttp.request(MusicSource.KUGOU, url, cookies, "").json()
        if (response.number("status") != 1L) {
            val code = response.number("error_code")
            DangoLogger.warn("Kugou web session exchange failed: error {} {}", code, response.text("data"))
            throw SourceAccessException("Kugou rejected the login (error $code). Refresh the QR code and try again.")
        }
        return response
    }

    fun encryptToken(token: String, key: String, now: Long): Pair<String, String> {
        require(key.matches(Regex("[A-Z0-9]{16}")))
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(digest.toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec(digest.takeLast(16).toByteArray(Charsets.US_ASCII)))
        val payload = JsonObject().apply { addProperty("token", token) }.toString()
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        val metadata = JsonObject().apply { addProperty("clienttime_ms", now); addProperty("key", key) }.toString().toByteArray(Charsets.US_ASCII)
        require(metadata.size < 128)
        // The web protocol places ASCII metadata at the front of a zero-filled RSA block.
        val block = ByteArray(128)
        System.arraycopy(metadata, 0, block, 0, metadata.size)
        val encoded = BigInteger(1, block).modPow(BigInteger.valueOf(65537), BigInteger(LOGIN_MODULUS, 16)).toString(16)
        return encoded.padStart((encoded.length + 3) / 4 * 4, '0') to encrypted
    }
}
