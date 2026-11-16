package me.sorihiko.dango.music

import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
import java.net.CookieManager
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * 酷狗概念版：和普通版是同一套账号，但以概念版 App 的身份发请求（App 编号、版本、签名都不一样）。
 * 概念版的会员（包括每天能领的免费会员）只认这个身份，所以用概念版扫码登录的账号，取歌和领会员都走这里。
 * 搜索、排行榜、歌词这些公开内容两边一样，照旧用普通的接口。
 */
internal object KugouLite {
    private const val APP_ID = "3116"
    private const val CLIENT_VERSION = "11440"
    /** 取歌地址时酷狗要求的版本号（和上面的不同） */
    private const val URL_VERSION = "11430"
    private const val SIGN_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
    private const val URL_KEY_SALT = "185672dd44712f60bb1736df5a377e82"
    private const val WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    private const val USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"
    const val QR_PAGE = "https://h5.kugou.com/apps/loginQRCode/html/index.html"

    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun sign(salt: String, params: Map<String, String>, body: String) =
        md5(salt + params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + body + salt)

    private fun base(account: NativeAccount?): LinkedHashMap<String, String> {
        val device = KugouDevice.get()
        return linkedMapOf("dfid" to "-", "mid" to device.mid, "uuid" to "-", "appid" to APP_ID, "clientver" to CLIENT_VERSION,
            "clienttime" to (System.currentTimeMillis() / 1000).toString()).apply {
            if (account != null) { put("token", account.token); put("userid", account.userId) }
        }
    }

    private fun headers(params: Map<String, String>) = mutableMapOf(
        "User-Agent" to USER_AGENT, "dfid" to params.getValue("dfid"), "mid" to params.getValue("mid"), "clienttime" to params.getValue("clienttime"),
        "kg-rc" to "1", "kg-rec" to "1", "kg-thash" to "5d816a0", "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F")

    /** 概念版 App 的请求：[body] 为 null 时是 GET */
    fun request(url: String, values: Map<String, String>, account: NativeAccount?, body: String? = null,
                contentType: String = "application/json", extra: Map<String, String> = emptyMap()): JsonObject {
        val params = base(account)
        params.putAll(values)
        params["signature"] = sign(SIGN_SALT, params, body.orEmpty())
        val response = AuthHttp.request(MusicSource.KUGOU, url + "?" + AuthHttp.form(params), account?.cookies ?: CookieManager(),
            body, contentType, headers(params) + extra)
        return response.json()
    }

    /** 扫码登录用的网页签名请求（App 编号用概念版的） */
    fun web(url: String, values: Map<String, String>, cookies: CookieManager): JsonObject {
        val params = base(null)
        params.putAll(values)
        params["signature"] = sign(WEB_SALT, params, "")
        return AuthHttp.request(MusicSource.KUGOU, url + "?" + AuthHttp.form(params), cookies).json()
    }

    // ------------------------------------------------------------ 扫码登录

    /** 申请一个概念版的登录二维码：返回 [二维码 key, 服务器给的二维码图片（可能为空）] */
    fun createQr(cookies: CookieManager): Pair<String, String> {
        val root = web("https://login-user.kugou.com/v2/qrcode", mapOf("appid" to "1001", "type" to "1", "plat" to "4",
            "qrcode_txt" to "$QR_PAGE?appid=$APP_ID&", "srcappid" to "2919"), cookies)
        check(root.number("status") == 1L) { "Kugou lite QR rejected: ${root.number("error_code")}" }
        val data = root.obj("data") ?: error("No Kugou lite QR")
        val key = data.text("qrcode")
        check(key.isNotEmpty())
        return key to data.text("qrcode_img")
    }

    /** 查扫码状态：返回 data（status 1 等待、2 已扫码、4 已确认，4 时带 token / userid） */
    fun pollQr(key: String, cookies: CookieManager): JsonObject {
        val root = web("https://login-user.kugou.com/v2/get_userinfo_qrcode", mapOf("plat" to "4", "appid" to APP_ID,
            "srcappid" to "2919", "qrcode" to key, "dev" to KugouDevice.get().dev), cookies)
        check(root.number("status") == 1L) { "Kugou lite QR poll rejected: ${root.number("error_code")}" }
        return root.obj("data") ?: error("No Kugou lite QR status")
    }

    // ------------------------------------------------------------ 取歌

    /** 概念版身份取播放地址：按设置的音质从高往低要（MP3 或 FLAC） */
    fun songUrl(track: NeteaseTrack, account: NativeAccount): NeteaseApi.SongUrl? {
        ensureDailyVip(account)
        for (quality in PlaybackQuality.wanted.kugouQualities) {
            val root = urlRequest(track, account, quality)
            checkVerification(root)
            val url = playUrl(root) ?: continue
            if (root.text("extName").let { it.isNotEmpty() && !it.equals("mp3", true) && !it.equals("flac", true) }) continue
            val seconds = root.number("timeLength")
            // 响度：volume 是增益（分贝），volume_peak 是峰值（分贝，换成线性）
            fun decibels(key: String) = root.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toFloatOrNull()?.takeIf { it.isFinite() }
            val gain = decibels("volume") ?: 0f
            val peak = decibels("volume_peak")?.let { Math.pow(10.0, it / 20.0).toFloat() } ?: 0f
            return NeteaseApi.SongUrl(url, seconds in 1 until track.durationMs / 1000 - 5, gain, peak)
        }
        return null
    }

    private fun urlRequest(track: NeteaseTrack, account: NativeAccount, quality: String): JsonObject {
        val hash = track.nativeId.lowercase(java.util.Locale.ROOT)
        val params = base(account)
        // 这个接口每次带一个随机的 dfid
        val random = SecureRandom()
        val letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        params["dfid"] = (0 until 24).map { letters[random.nextInt(letters.length)] }.joinToString("")
        params.putAll(mapOf("album_id" to track.albumId.ifEmpty { "0" }, "area_code" to "1", "hash" to hash, "ssa_flag" to "is_fromtrack",
            "version" to URL_VERSION, "page_id" to "967177915", "quality" to quality, "album_audio_id" to "0", "behavior" to "play",
            "pid" to "411", "cmd" to "26", "pidversion" to "3001", "IsFreePart" to "0", "ppage_id" to "356753938,823673182,967485191",
            "cdnBackup" to "1", "module" to "", "clientver" to URL_VERSION))
        params["key"] = md5(hash + URL_KEY_SALT + APP_ID + params.getValue("mid") + account.userId)
        params["signature"] = sign(SIGN_SALT, params, "")
        val response = AuthHttp.request(MusicSource.KUGOU, "https://gateway.kugou.com/v5/url?" + AuthHttp.form(params), account.cookies,
            null, "application/json", headers(params) + mapOf("x-router" to "trackercdn.kugou.com"))
        return response.json()
    }

    /** 播放地址可能是数组、也可能是一串用逗号隔开的地址：取第一个 http(s) 的 */
    private fun playUrl(root: JsonObject): String? {
        val raw = root.get("url") ?: root.get("play_url") ?: return null
        val values = if (raw.isJsonArray) raw.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            else if (raw.isJsonPrimitive) listOf(raw.asString) else emptyList()
        return values.flatMap { it.split(Regex(",\\s*(?=https?://)")) }.map { it.trim() }
            .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
    }

    // ------------------------------------------------------------ 每日免费会员

    @Volatile private var claimedDay = ""

    /**
     * 概念版每天可以领一天免费会员：没有会员时自动领。每个账号每天最多试一次（按北京时间算"一天"），
     * 领不到也不影响播放（只是听不了会员歌）。
     */
    fun ensureDailyVip(account: NativeAccount) {
        if (!account.concept) return
        val day = SimpleDateFormat("yyyy-MM-dd").apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.format(Date())
        val mark = account.userId + "@" + day
        synchronized(this) {
            if (claimedDay == mark) return
            claimedDay = mark
        }
        try {
            val union = request("https://kugouvip.kugou.com/v1/get_union_vip",
                mapOf("busi_type" to "concept", "opt_product_types" to "dvip,qvip", "product_type" to "svip"), account)
            if (errorCode(union) != 0L) {
                DangoLogger.warn("Kugou lite VIP check failed: error {}", errorCode(union))
                return
            }
            if (isVip(union)) {
                DangoLogger.info("Kugou lite: VIP already active today")
                return
            }
            val form = "application/x-www-form-urlencoded"
            val claim = request("https://gateway.kugou.com/youth/v1/recharge/receive_vip_listen_song",
                mapOf("source_id" to "90139", "receive_day" to day), account, "", form)
            if (!succeeded(claim)) {
                DangoLogger.warn("Kugou lite daily VIP claim failed: status {} error {}", claim.number("status"), errorCode(claim))
                return
            }
            val upgrade = request("https://gateway.kugou.com/youth/v1/listen_song/upgrade_vip_reward",
                mapOf("kugouid" to account.userId, "ad_type" to "1"), account, "", form)
            if (succeeded(upgrade)) DangoLogger.info("Kugou lite: claimed today's free VIP")
            else DangoLogger.warn("Kugou lite VIP upgrade failed: status {} error {}", upgrade.number("status"), errorCode(upgrade))
        } catch (e: Exception) {
            DangoLogger.warn("Kugou lite daily VIP failed: {}", e.toString())
        }
    }

    private fun errorCode(root: JsonObject): Long =
        root.number("errcode").takeIf { it != 0L } ?: root.number("error_code").takeIf { it != 0L }
            ?: root.obj("data")?.let { it.number("errcode").takeIf { c -> c != 0L } ?: it.number("error_code") } ?: 0L

    private fun succeeded(root: JsonObject): Boolean {
        val status = if (root.has("status")) root.number("status") else root.obj("data")?.number("status") ?: 1L
        return errorCode(root) == 0L && status == 1L
    }

    private fun isVip(root: JsonObject): Boolean {
        val data = root.obj("data") ?: return false
        if (data.number("vip_type") > 0 || data.number("is_vip") > 0) return true
        return data.array("busi_vip").any { it.isJsonObject && (it.asJsonObject.number("is_vip") > 0 || it.asJsonObject.number("vip_type") > 0) }
    }

    /** 酷狗要求在 App 里完成一次验证时，给出明确的提示 */
    fun checkVerification(root: JsonObject) {
        val code = errorCode(root)
        val message = root.text("error").ifEmpty { root.text("error_msg") }
        if (code == 20028L || message.contains("本次请求需要验证"))
            throw SourceAccessException("Kugou asks for a security check. Open the Kugou app, play a song there, then try again.")
    }
}
