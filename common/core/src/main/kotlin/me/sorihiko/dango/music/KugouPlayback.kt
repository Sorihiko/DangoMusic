package me.sorihiko.dango.music

import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
import java.math.BigInteger
import java.security.MessageDigest
import java.net.SocketTimeoutException

/** 普通版先沿用网页登录；目录信息不匹配时退回 hash，再尝试普通版 MP3 接口。 */
internal class KugouPlayback @JvmOverloads constructor(
    private val web: (NeteaseTrack, NativeAccount, Boolean) -> JsonObject = { t, a, metadata -> KugouWeb.songInfo(t, a, metadata) },
    private val app: (NeteaseTrack, NativeAccount, String, Boolean) -> JsonObject = ::request,
    private val accept: (NeteaseApi.SongUrl) -> Boolean = { true },
) {
    fun resolve(track: NeteaseTrack, account: NativeAccount): NeteaseApi.SongUrl? {
        require(!account.concept && account.source == MusicSource.KUGOU)
        val variants = if (track.albumId.isNotBlank() || track.albumAudioId.isNotBlank()) listOf(true, false) else listOf(false)
        var trial: NeteaseApi.SongUrl? = null
        val seen = HashSet<String>()
        fun candidate(root: JsonObject): NeteaseApi.SongUrl? {
            for (item in candidates(root, track)) {
                if (!seen.add(item.url) || (item.trialOnly && trial != null)) continue
                if (accept(item)) {
                    if (!item.trialOnly) return item
                    trial = item
                }
            }
            return null
        }
        for (metadata in variants) {
            val root = try { web(track, account, metadata) } catch (_: SocketTimeoutException) { break }
            checked(root, "web")
            candidate(root)?.let { return it }
        }
        // 网页会话不一定被 App 接口接受；拒绝时保留网页的可用结果，不把整个账号误判成失效。
        appLoop@ for (quality in listOf("320", "128")) for (metadata in variants) {
            val root = try { app(track, account, quality, metadata) } catch (_: SocketTimeoutException) { break@appLoop }
            checked(root, "mp3-$quality")
            candidate(root)?.let { return it }
        }
        return trial
    }

    private fun checked(root: JsonObject, stage: String) {
        val code = root.number("error_code").takeIf { it != 0L } ?: root.number("err_code").takeIf { it != 0L } ?: root.number("errcode")
        DangoLogger.info("Kugou playback stage={} status={} code={}", stage, root.number("status"), code)
        if (code == 30020L) throw SourceAccessException("Kugou requires additional security verification. Please try again later.")
    }

    internal fun parse(root: JsonObject, track: NeteaseTrack): NeteaseApi.SongUrl? = candidates(root, track).firstOrNull()

    internal fun candidates(root: JsonObject, track: NeteaseTrack): List<NeteaseApi.SongUrl> {
        val data = root.obj("data") ?: root
        if (root.number("status") != 1L && data.number("status") != 1L) return emptyList()
        val format = data.text("extName").ifEmpty { data.text("extname") }
        if (format.isNotEmpty() && format.lowercase(java.util.Locale.ROOT) !in setOf("mp3", "ogg", "vorbis", "flac")) return emptyList()
        val urls = sequenceOf("play_url", "url", "backup_url").flatMap { key ->
            val raw = data.get(key)
            when {
                raw == null || raw.isJsonNull -> emptySequence<String>()
                raw.isJsonArray -> raw.asJsonArray.asSequence().filter { it.isJsonPrimitive }.map { it.asString }
                raw.isJsonPrimitive -> sequenceOf(raw.asString)
                else -> emptySequence<String>()
            }
        }.flatMap { it.split(Regex(",\\s*(?=https?://)")).asSequence() }.map { it.trim() }
            .filter { (it.startsWith("https://") || it.startsWith("http://")) }.distinct().take(3).toList()
        val ms = data.number("timelength").takeIf { it > 0 } ?: data.number("timeLength") * 1000
        val cover = data.text("sizable_cover").ifEmpty { data.text("img") }.replace("{size}", "300")
        if (cover.isNotEmpty()) track.coverUrl = cover
        return urls.map { NeteaseApi.SongUrl(it, ms > 0 && track.durationMs > 5000 && ms < track.durationMs - 5000) }
    }

    companion object {
        private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun request(track: NeteaseTrack, account: NativeAccount, quality: String, metadata: Boolean): JsonObject {
            val hash = track.nativeId.lowercase(java.util.Locale.ROOT)
            val mid = BigInteger(md5(account.deviceId), 16).toString()
            val params = linkedMapOf("appid" to "1005", "clientver" to "11430", "clienttime" to (System.currentTimeMillis() / 1000).toString(),
                "mid" to mid, "uuid" to account.deviceId, "dfid" to NativeAccounts.cookie(account.cookies, "kg_dfid").ifEmpty { "-" },
                "userid" to account.userId, "token" to account.token, "hash" to hash, "quality" to quality,
                "album_id" to (if (metadata) track.albumId else "").ifEmpty { "0" }, "album_audio_id" to (if (metadata) track.albumAudioId else "").ifEmpty { "0" },
                "area_code" to "1", "ssa_flag" to "is_fromtrack", "version" to "11430", "page_id" to "151369488", "ppage_id" to "463467626,350369493,788954147",
                "behavior" to "play", "pid" to "2", "cmd" to "26", "pidversion" to "3001", "IsFreePart" to "0", "cdnBackup" to "1", "module" to "")
            params["key"] = md5(hash + "57ae12eb6890223e355ccfcb74edf70d" + "1005" + mid + account.userId)
            params["signature"] = KugouGateway.signature(params, "")
            return AuthHttp.request(MusicSource.KUGOU, "https://gateway.kugou.com/v5/url?" + AuthHttp.form(params), account.cookies,
                null, "application/json", mapOf("x-router" to "trackercdn.kugou.com", "mid" to mid, "dfid" to params.getValue("dfid"),
                    "clienttime" to params.getValue("clienttime"), "kg-rc" to "1", "kg-rec" to "1", "kg-thash" to "5d816a0", "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F")).json()
        }
    }
}
