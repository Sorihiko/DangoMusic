package me.sorihiko.dango.music

import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
import java.math.BigInteger
import java.net.CookieManager
import java.security.MessageDigest
import java.util.UUID

/** Signed read-only catalog requests. Playback continues to use the existing web session. */
internal object KugouGateway {
    private const val SALT = "OIlwieks28dk2k092lksi2UIkp"
    private val guestDevice = UUID.randomUUID().toString().replace("-", "")
    internal fun signature(params: Map<String, String>, body: String): String = md5(
        SALT + params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + body + SALT)
    internal fun webSignature(params: Map<String, String>, body: String): String {
        val salt = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
        return md5(salt + params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + body + salt)
    }
    private fun md5(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun request(path: String, values: Map<String, String>, body: JsonObject?, account: NativeAccount?): JsonObject {
        require(path.startsWith("/") && !path.contains('?') && !path.contains(".."))
        require(account == null || account.source == MusicSource.KUGOU)
        if (account?.concept == true) return concept(path, values, body, account)
        val device = account?.deviceId?.takeIf { it.isNotEmpty() } ?: guestDevice
        val mid = BigInteger(md5(device),16).toString()
        val now = (System.currentTimeMillis()/1000).toString()
        val dfid = account?.let { NativeAccounts.cookie(it.cookies,"kg_dfid") }?.takeIf { it.isNotEmpty() } ?: "-"
        val params = linkedMapOf("appid" to "1005", "clientver" to "20489", "clienttime" to now,
            "dfid" to dfid, "mid" to mid, "uuid" to device, "userid" to (account?.userId ?: "0"), "token" to (account?.token ?: ""))
        params.putAll(values)
        val text = body?.toString().orEmpty()
        val headers = mutableMapOf(
                "User-Agent" to "Android15-AndroidPhone-20489-46-0-wifi", "mid" to mid, "dfid" to dfid,
                "clienttime" to now, "kg-rc" to "1", "kg-rec" to "1", "kg-thash" to "5d816a0", "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F")
        var route = path
        if (account != null && path.startsWith("/cloudlist.service/")) {
            // The stored token belongs to the website login, not to Android appid=1005.
            // Use the matching H5 profile and cloudlist router for private lists.
            route = path.removePrefix("/cloudlist.service").replace("/v8/get_all_list", "/v7/get_all_list")
            params["appid"] = "1014"; params["clientver"] = "20000"; params["srcappid"] = "2919"
            params["clienttime"] = System.currentTimeMillis().toString(); params["uuid"] = params.getValue("clienttime")
            params["mid"] = NativeAccounts.cookie(account.cookies,"kg_mid").ifEmpty { device }
            headers["mid"] = params.getValue("mid"); headers["clienttime"] = params.getValue("clienttime")
            headers["User-Agent"] = "Mozilla/5.0"; headers["x-router"] = "cloudlist.service.kugou.com"
            params["signature"] = webSignature(params,text)
        } else params["signature"] = signature(params,text)
        val response = AuthHttp.request(MusicSource.KUGOU,"https://gateway.kugou.com$route?${AuthHttp.form(params)}",
            account?.cookies ?: CookieManager(),body?.toString(),"application/json", headers)
        val root = response.json()
        val code = root.number("error_code").takeIf { it != 0L } ?: root.number("err_code")
        DangoLogger.info("Kugou catalog route={} status={} code={}",path,root.number("status"),code)
        return checked(root, code, path == "/bsstrackercdngz/v2/query_musicclound_url")
    }

    /** 概念版登录的账号：私人歌单这些也要用概念版的身份去要（普通版那套网页登录它没有） */
    private fun concept(path: String, values: Map<String, String>, body: JsonObject?, account: NativeAccount): JsonObject {
        var route = path
        val extra = HashMap<String, String>()
        if (path.startsWith("/cloudlist.service/")) {
            route = path.removePrefix("/cloudlist.service").replace("/v8/get_all_list", "/v7/get_all_list")
            extra["x-router"] = "cloudlist.service.kugou.com"
        }
        val root = KugouLite.request("https://gateway.kugou.com$route", values, account, body?.toString(), "application/json", extra)
        KugouLite.checkVerification(root)
        val code = root.number("error_code").takeIf { it != 0L } ?: root.number("err_code")
        DangoLogger.info("Kugou lite catalog route={} status={} code={}", path, root.number("status"), code)
        return checked(root, code, path == "/bsstrackercdngz/v2/query_musicclound_url")
    }

    private fun checked(root: JsonObject, code: Long, cloudUrl: Boolean = false): JsonObject {
        if (root.number("status") != 1L) throw SourceAccessException(when (code) {
            30020L -> "Kugou requires additional security verification. Please try again later."
            20017L -> "Kugou login is no longer valid. Please log in again."
            else -> "Kugou library request failed. Please retry or log in again."
        })
        if (cloudUrl) return root.obj("data") ?: root
        return root.obj("data") ?: throw SourceAccessException("Kugou returned incomplete library data. Please retry.")
    }
}
