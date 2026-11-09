package me.sorihiko.dango.music

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import me.sorihiko.dango.lyrics.Lyrics
import java.util.concurrent.CancellationException

/**
 * 汽水音乐：登录态、签名和取流都通过 [SodaBridge]（Node 子进程）完成。
 * 播放走 FLAC 变体：下载加密的 MP4，再用 [SodaCrypto] 还原成标准 FLAC 交给播放器。
 */
object SodaMusicSource : MusicSourceApi {
    override val source = MusicSource.SODA

    private const val PC = "pc"
    private const val LUNA = "luna"

    override fun search(query: String): List<NeteaseTrack> {
        val body = data(SodaBridge.api("GET", PC, "/luna/pc/search/track", JsonObject().apply {
            addProperty("q", query)
            addProperty("cursor", "0")
            addProperty("count", "40")
            addProperty("search_method", "input")
        })) ?: return emptyList()
        val out = ArrayList<NeteaseTrack>()
        for (group in body.array("result_groups")) {
            val groupObject = group.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            for (item in groupObject.array("data")) {
                val entity = item.takeIf { it.isJsonObject }?.asJsonObject?.obj("entity") ?: continue
                val track = entity.obj("track_wrapper")?.obj("track") ?: entity.obj("track") ?: continue
                parseTrack(track)?.let { out += it }
            }
        }
        for (item in body.array("tracks")) {
            val track = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            parseTrack(track)?.let { out += it }
        }
        return out.distinctBy { it.key }.take(60)
    }

    override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? = null

    override fun lyrics(track: NeteaseTrack): Lyrics? {
        val body = try {
            data(SodaBridge.api("GET", LUNA, "/luna/h5/seo_track", JsonObject().apply {
                addProperty("track_id", track.nativeId)
                addProperty("device_platform", "web")
            }))
        } catch (_: Exception) {
            null
        } ?: return null
        val content = body.obj("lyric")?.text("content").orEmpty()
        val lines = if (content.isBlank()) emptyList() else KugouLyrics.parse(content)
        return if (lines.isEmpty()) null else Lyrics(lines, source.label)
    }

    override fun playlists(): List<NeteasePlaylist> {
        val body = data(SodaBridge.api("GET", PC, "/luna/pc/me/playlist")) ?: return emptyList()
        return body.array("playlists").mapNotNull { element ->
            val playlist = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            parsePlaylist(playlist, owned = true)
        }.distinctBy { it.id }.take(200)
    }

    override fun recommend(): List<NeteasePlaylist> {
        val reply = SodaBridge.api("POST", LUNA, "/luna/discover/mix", body = JsonObject().apply { addProperty("count", 12) })
        val body = data(reply) ?: return emptyList()
        val out = ArrayList<NeteasePlaylist>()
        for (block in body.array("inner_block")) {
            val blockObject = block.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            for (resource in blockObject.array("resources")) {
                val playlist = resource.takeIf { it.isJsonObject }?.asJsonObject?.obj("entity")?.obj("playlist") ?: continue
                parsePlaylist(playlist, owned = false)?.let { out += it }
            }
        }
        return out.distinctBy { it.id }.take(24)
    }

    override fun charts(): List<NeteasePlaylist> = emptyList()

    override fun playlistTracks(id: Long): List<NeteaseTrack> {
        val out = ArrayList<NeteaseTrack>()
        var cursor = ""
        repeat(4) {
            val body = data(SodaBridge.api("POST", LUNA, "/luna/playlist/detail", body = JsonObject().apply {
                addProperty("playlist_id", id.toString())
                addProperty("count", 100)
                if (cursor.isNotEmpty()) addProperty("cursor", cursor)
            })) ?: return out
            for (item in body.array("media_resources")) {
                val entity = item.takeIf { it.isJsonObject }?.asJsonObject?.obj("entity") ?: continue
                val track = entity.obj("track_wrapper")?.obj("track") ?: entity.obj("track") ?: continue
                parseTrack(track)?.let { out += it }
            }
            if (!body.get("has_more")?.takeIf { it.isJsonPrimitive }?.asBoolean.orFalse()) return out
            val next = body.text("next_cursor")
            if (next.isEmpty() || next == cursor) return out
            cursor = next
        }
        return out.distinctBy { it.key }
    }

    /**
     * 完整整曲：桥接拿变体 -> 只接受 FLAC -> 下载 -> spade/AES-CTR 还原。
     * 返回 null 没有意义（播放器已经在等），失败一律抛 [SourceAccessException] 给界面显示。
     */
    override fun prepareAudio(track: NeteaseTrack, active: () -> Boolean): MusicSourceApi.LoadedAudio? {
        require(track.source == source)
        if (NativeAccounts.get(source) == null) throw SourceAccessException("Log in to the Soda Music desktop client first.")
        val reply = SodaBridge.track(track.nativeId)
        if (!reply.ok) throw SourceAccessException(reply.error)
        val body = reply.body ?: throw SourceAccessException("Soda Music returned no playback information.")
        val streamMs = body.number("streamMs")
        if (track.durationMs > 0 && streamMs > 0 && streamMs + 2000 < track.durationMs) {
            throw SourceAccessException("Soda Music only returned a preview. Check your membership in the desktop client.")
        }
        val flac = body.array("variants").mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
            .filter { it.text("codec") == "flac" && it.text("url").startsWith("https://") }
            .maxByOrNull { it.number("bitrate") }
            ?: throw SourceAccessException("This song has no FLAC stream. The built-in player cannot decode Soda Music AAC yet.")
        if (!active()) throw CancellationException()
        val encrypted = SourceHttp.bytes(flac.text("url"), "https://music.douyin.com/", 96 * 1024 * 1024)
            ?: throw SourceAccessException("Unable to download this song from Soda Music.")
        if (!active()) throw CancellationException()
        val audio = try {
            SodaCrypto.flacFromMp4(encrypted, flac.text("spadeA"))
        } catch (e: Exception) {
            throw SourceAccessException(if (e is SourceAccessException) e.message.orEmpty() else "Unable to decrypt this Soda Music stream.")
        }
        return MusicSourceApi.LoadedAudio(audio, NeteaseApi.SongUrl(flac.text("url"), false))
    }

    /** 连接汽水账号（扫码登录成功后，或本机客户端已登录）；没登录返回 false。 */
    fun connect(): Boolean {
        val reply = SodaBridge.status()
        if (!reply.ok) throw SourceAccessException(reply.error)
        if (!reply.flag("loggedIn")) return false
        var userId = reply.text("userId")
        var nickname = reply.text("nickname")
        var avatar = reply.text("avatar")
        // 刚确认登录时资料接口偶尔会慢一拍，重试两次再决定。
        for (attempt in 0 until 2) {
            if (validId(userId)) break
            Thread.sleep(1200)
            val retry = SodaBridge.status()
            if (retry.ok && validId(retry.text("userId"))) {
                userId = retry.text("userId")
                nickname = retry.text("nickname")
                avatar = retry.text("avatar")
            }
        }
        if (!validId(userId)) {
            userId = NativeAccounts.get(source)?.userId
                ?: reply.text("deviceId").filter { it.isDigit() }.take(18).ifEmpty { "1" }
        }
        NativeAccounts.save(NativeAccount(
            source, userId, nickname.ifEmpty { "Soda Music" }, "client", reply.text("deviceId"),
            java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER), avatarUrl = avatar,
        ))
        return true
    }

    private fun validId(value: String) = value.matches(Regex("[0-9]{1,20}")) && value.any { it != '0' }

    private fun data(reply: SodaBridge.Reply): JsonObject? {
        if (!reply.ok) throw SourceAccessException(reply.error)
        return reply.body?.obj("data")
    }

    private fun parsePlaylist(playlist: JsonObject, owned: Boolean): NeteasePlaylist? {
        val id = playlist.text("id").toLongOrNull()?.takeIf { it > 0 } ?: return null
        val title = playlist.text("title").ifEmpty { playlist.text("name") }.ifEmpty { return null }
        val count = playlist.number("count_tracks").toInt().takeIf { it > 0 }
            ?: playlist.number("track_count").toInt().takeIf { it > 0 } ?: 0
        return NeteasePlaylist(id, cleanSourceText(title), count, cover(playlist.get("url_cover") ?: playlist.get("cover_url")), owned)
    }

    private fun parseTrack(track: JsonObject): NeteaseTrack? {
        val id = track.text("id")
        if (!id.matches(Regex("[0-9]{1,20}"))) return null
        val name = cleanSourceText(track.text("name")).ifEmpty { return null }
        val artists = track.array("artists").mapNotNull { element ->
            val artist = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val value = artist.text("name").ifEmpty { artist.text("simple_display_name").ifEmpty { artist.obj("user_info")?.text("nickname").orEmpty() } }
            if (value.isEmpty()) null else value to artist.text("id")
        }
        val album = track.obj("album")
        val albumId = album?.text("id").orEmpty()
        return NeteaseTrack(
            MusicSources.stableId(source, id), name, artists.map { cleanSourceText(it.first) },
            track.number("duration"), cover(album?.get("url_cover") ?: album?.get("cover_url")),
            source, id, id, albumId,
        ).apply {
            artistIds = artists.map { it.second.takeIf { value -> value.matches(Regex("[0-9]{1,20}")) }.orEmpty() }
        }
    }

    private fun cover(value: JsonElement?): String {
        if (value == null || value.isJsonNull || !value.isJsonPrimitive && !value.isJsonObject) return ""
        if (value.isJsonPrimitive) return value.asString
        val objectValue = value.asJsonObject
        val base = objectValue.array("urls").firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString
            ?: objectValue.text("url")
        if (base.isEmpty()) return ""
        val uri = objectValue.text("uri")
        // 汽水的图床要带模板后缀，直接 base+uri 会返回 400。
        val prefix = objectValue.text("template_prefix")
        val suffix = if (prefix.isEmpty()) "" else "~" + prefix + "-crop-center:300:300.jpg"
        return when {
            uri.isEmpty() -> base + suffix
            base.endsWith(uri) -> base + suffix
            else -> base + uri + suffix
        }
    }

    private fun Boolean?.orFalse(): Boolean = this == true
}