package me.sorihiko.dango.music

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Official private-cloud protocol, implemented with JDK crypto; no upload or deletion. */
internal class KugouCloud(
    private val page: (NativeAccount, Int) -> JsonObject = Companion::requestPage,
    private val call: (String, Map<String, String>, JsonObject?, NativeAccount?) -> JsonObject = KugouGateway::request,
) {
    fun tracks(account: NativeAccount): List<NeteaseTrack> {
        require(account.source == MusicSource.KUGOU)
        val output = LinkedHashMap<String, NeteaseTrack>()
        val pages = HashSet<String>()
        var received = 0
        for (number in 1..1000) {
            val root = page(account, number)
            if (root.number("status") != 1L)
                throw SourceAccessException("Kugou cloud request failed. Please retry or log in again.")
            val data = root.obj("data") ?: root
            val counts = listOf(data, root).distinct().flatMap { block ->
                listOf("total", "count", "total_count").mapNotNull { block.text(it).toLongOrNull()?.takeIf { value -> value >= 0 } }
            }
            val more = listOf(data, root).any { it.number("has_more") == 1L || it.number("hasmore") == 1L }
            val rows = rows(data) ?: run {
                // Empty cloud responses may omit the list entirely. Do not infer zero from a missing count.
                val malformed = listOf("list", "lists", "info", "songs", "data").any { field ->
                    val value = data.get(field)
                    value != null && !value.isJsonNull && !value.isJsonObject && !value.isJsonArray
                }
                if (!malformed && !more && counts.isNotEmpty() && counts.all { it == 0L }) {
                    TrackLoadProgress.report(received, received)
                    return output.values.toList()
                }
                me.sorihiko.dango.DangoLogger.warn("Kugou cloud: missing list page={} dataObject={} zeroCount={} positiveCount={} more={}",
                    number, root.obj("data") != null, counts.any { it == 0L }, counts.any { it > 0L }, more)
                throw SourceAccessException("Kugou returned incomplete cloud data. Please retry.")
            }
            if (rows.size() > 0 && !pages.add(md5(rows.toString())))
                throw SourceAccessException("Kugou repeated a cloud page. Please retry.")
            for (item in rows) {
                if (!item.isJsonObject) continue
                parseTrack(item.asJsonObject)?.let { if (!output.containsKey(it.key)) output[it.key] = it }
            }
            received += rows.size()
            val total = counts.firstOrNull { it > 0 } ?: 0L
            TrackLoadProgress.report(received, total.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            if (TrackLoadProgress.wantsPartial) TrackLoadProgress.partial(output.values.toList())
            if ((total > 0 && received >= total) || (!more && total <= 0 && rows.size() < 100)) return output.values.toList()
            if (rows.size() == 0) throw SourceAccessException("Kugou returned incomplete cloud data. Please retry.")
        }
        throw SourceAccessException("Kugou returned too many cloud pages. Please retry.")
    }

    fun candidates(track: NeteaseTrack, account: NativeAccount): List<NeteaseApi.SongUrl> {
        require(CloudDrives.privateTrack(track) && account.source == MusicSource.KUGOU)
        val hash = track.nativeId.lowercase(Locale.ROOT)
        require(hash.matches(Regex("[a-f0-9]{32}")))
        val data = call("/bsstrackercdngz/v2/query_musicclound_url", mapOf(
            "hash" to hash, "ssa_flag" to "is_fromtrack", "version" to "20102", "ssl" to "0",
            "album_audio_id" to "0", "pid" to "20026", "audio_id" to "0", "kv_id" to "2",
            "key" to md5("musicclound" + hash + "20026" + "ebd1ac3134c880bda6a2194537843caa0162e2e7"),
            "bucket" to "musicclound", "name" to "", "with_res_tag" to "0"), null, account)
        val format = data.text("extName").ifEmpty { data.text("extname") }.lowercase(Locale.ROOT)
        if (format.isNotEmpty() && format !in setOf("mp3", "ogg", "vorbis", "flac"))
            throw SourceAccessException("This cloud audio format is not supported. Only MP3, Ogg Vorbis and FLAC are supported.")
        // Gateway has already checked the outer status; retain its unmodified URL fields.
        return KugouPlayback().candidates(JsonObject().apply { addProperty("status", 1); add("data", data) }, track)
    }

    companion object {
        private const val ORDINARY_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB"
        private const val CONCEPT_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"
        private val random = SecureRandom()
        internal fun md5(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        /** The protocol derives ASCII AES key/IV from the two halves of an MD5 hex digest. */
        internal fun crypt(bytes: ByteArray, key: String, encrypt: Boolean): ByteArray {
            require(key.matches(Regex("[a-z0-9]{6}")))
            val digest = md5(key)
            return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    SecretKeySpec(digest.substring(0, 16).toByteArray(Charsets.US_ASCII), "AES"),
                    IvParameterSpec(digest.substring(16).toByteArray(Charsets.US_ASCII)))
                doFinal(bytes)
            }
        }

        internal fun seal(key: String, account: NativeAccount): String {
            val payload = JsonObject().apply {
                addProperty("aes", key); addProperty("uid", account.userId.toLong()); addProperty("token", account.token)
            }.toString().toByteArray(Charsets.UTF_8)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(
                Base64.getDecoder().decode(if (account.concept) CONCEPT_KEY else ORDINARY_KEY)))
            return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.ENCRYPT_MODE, publicKey)
                doFinal(payload).joinToString("") { "%02X".format(it.toInt() and 255) }
            }
        }

        private fun requestPage(account: NativeAccount, page: Int): JsonObject {
            try {
                val key = (1..6).map { ('a'.code + random.nextInt(26)).toChar() }.joinToString("")
                val now = (System.currentTimeMillis() / 1000).toString()
                val app = if (account.concept) "3116" else "1005"
                val version = if (account.concept) "11440" else "20489"
                val salt = if (account.concept) "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA" else "OIlwieks28dk2k092lksi2UIkp"
                val mid = KugouDevice.get().mid
                val params = linkedMapOf("clienttime" to now, "mid" to mid, "key" to md5(app + salt + version + now),
                    "clientver" to version, "appid" to app, "p" to seal(key, account))
                val payload = JsonObject().apply { addProperty("page", page); addProperty("pagesize", 100); addProperty("getkmr", 1) }
                val response = AuthHttp.requestBytes(MusicSource.KUGOU,
                    "https://mcloudservice.kugou.com/v1/get_list?" + AuthHttp.form(params), account.cookies,
                    crypt(payload.toString().toByteArray(Charsets.UTF_8), key, true), "application/octet-stream",
                    mapOf("User-Agent" to "Android15-AndroidPhone-$version-46-0-wifi", "mid" to mid, "clienttime" to now, "dfid" to "-"))
                if (response.code !in 200..299) throw IllegalStateException()
                val decrypted = crypt(response.body, key, false)
                return JsonParser().parse(String(decrypted, Charsets.UTF_8)).asJsonObject
            } catch (_: Exception) {
                // Never expose encrypted login query strings or private response data.
                throw SourceAccessException("Kugou cloud request failed. Please retry or log in again.")
            }
        }

        private fun rows(data: JsonObject): JsonArray? {
            for (name in listOf("list", "lists", "info", "songs", "data")) {
                val value = data.get(name) ?: continue
                if (value.isJsonArray) return value.asJsonArray
                if (value.isJsonObject) rows(value.asJsonObject)?.let { return it }
            }
            return null
        }

        internal fun parseTrack(row: JsonObject): NeteaseTrack? {
            val normalized = JsonObject()
            for (entry in row.entrySet()) normalized.add(entry.key, entry.value)
            fun alias(target: String, vararg fields: String) {
                if (normalized.text(target).isNotBlank()) return
                fields.asSequence().map { row.text(it) }.firstOrNull { it.isNotBlank() }?.let { normalized.addProperty(target, it) }
            }
            alias("hash", "FileHash", "fileHash", "Hash")
            alias("filename", "FileName", "fileName", "name", "audio_name")
            alias("songname", "SongName", "songName", "ori_audio_name")
            alias("singername", "SingerName", "Singer")
            alias("imgurl", "Image", "image", "cover", "sizable_cover")
            val milliseconds = sequenceOf("timelen", "timeLen", "timelength").map { row.number(it) }.firstOrNull { it > 0 }
            val duration = sequenceOf("timeLength", "Duration", "duration").map { row.number(it) }.firstOrNull { it > 0 } ?: 0L
            normalized.addProperty("timelen", milliseconds ?: if (duration in 1..9999) duration * 1000 else duration)
            val parsed = KugouMusicSource.parseTrack(normalized) ?: return null
            return NeteaseTrack(MusicSources.stableId(MusicSource.KUGOU, "cloud:" + parsed.nativeId), parsed.name,
                parsed.artists, parsed.durationMs, parsed.coverUrl, MusicSource.KUGOU, parsed.nativeId, parsed.mediaId, parsed.albumId).apply {
                cloud = true
                albumAudioId = parsed.albumAudioId
            }
        }
    }
}
