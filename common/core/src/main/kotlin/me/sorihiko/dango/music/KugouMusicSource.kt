package me.sorihiko.dango.music

import com.google.gson.JsonObject
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider
import java.util.Base64

/** Kugou library and playback share the existing native login and source-aware player. */
object KugouMusicSource : MusicSourceApi {
    override val source = MusicSource.KUGOU
    private const val REFERER = "https://m.kugou.com/"
    private val catalog = KugouCatalog()
    override fun playlists() = catalog.playlists()
    override fun recommend() = catalog.recommend()
    override fun charts() = catalog.charts()
    override fun playlistTracks(id: Long) = KugouArtists.albumTracksFor(id) ?: if (id == CloudDrives.KUGOU_ID) {
        val account = NativeAccounts.get(source) ?: throw SourceAccessException("Log in to Kugou to read your cloud drive.")
        KugouCloud().tracks(account)
    } else catalog.tracks(id)

    override val hasArtists get() = true
    override fun artist(id: String) = KugouArtists.artist(id)
    override fun artistAlbums(id: String) = KugouArtists.artistAlbums(id)
    override fun findArtist(name: String) = KugouArtists.findArtist(name)

    override fun search(query: String): List<NeteaseTrack> {
        val root = SourceHttp.json("https://msearchretry.kugou.com/api/v3/search/song?format=json&page=1&pagesize=40&showtype=1&keyword=${SourceHttp.enc(query)}", REFERER)
        check(root.number("status") == 1L) { "Kugou search rejected" }
        return parseSearch(root)
    }

    internal fun parseSearch(root: JsonObject): List<NeteaseTrack> {
        val list = root.getAsJsonObject("data")?.getAsJsonArray("info") ?: return emptyList()
        return list.mapNotNull { entry -> if (entry.isJsonObject) parseTrack(entry.asJsonObject) else null }.distinctBy { it.key }
    }

    internal fun parseTrack(song: JsonObject): NeteaseTrack? {
        val audio = song.obj("audio_info") ?: JsonObject()
        val album = song.obj("album_info") ?: song.obj("albumInfo") ?: JsonObject()
        fun value(vararg names: String): String = names.asSequence().map { song.text(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        // 排行榜、搜索与云歌单的大小写和嵌套层级不同；不能拿高音质 hash 冒充标准版。
        val hash = sequenceOf(value("hash", "Hash", "filehash", "FileHash", "fileHash", "audio_hash", "audioHash"), audio.text("hash_128"), audio.text("hash"))
            .map { it.trim().uppercase(java.util.Locale.ROOT) }.firstOrNull { it.matches(Regex("[A-F0-9]{32}")) } ?: return null
        if (!hash.matches(Regex("[A-F0-9]{32}"))) return null
        val filename = song.text("filename").ifEmpty { song.text("FileName").ifEmpty { song.text("name") } }
        val title = value("songname", "SongName", "songName", "audio_name").ifEmpty { filename.substringAfter(" - ",filename) }
        // 歌手 ID 只有部分接口给（排行榜、歌单的 singerinfo / authors），搜索结果里没有：点进歌手页时按名字找
        val ids = song.array("singerinfo").mapNotNull { if (it.isJsonObject && it.asJsonObject.text("name").isNotBlank()) it.asJsonObject.text("id") else null }
            .ifEmpty { song.array("authors").mapNotNull { if (it.isJsonObject && it.asJsonObject.text("author_name").isNotBlank()) it.asJsonObject.text("author_id") else null } }
        val names = song.array("singerinfo").mapNotNull { if (it.isJsonObject) it.asJsonObject.text("name").takeIf { s -> s.isNotBlank() } else null }
            .ifEmpty { song.array("authors").mapNotNull { if (it.isJsonObject) it.asJsonObject.text("author_name").takeIf { s -> s.isNotBlank() } else null } }
            .ifEmpty { song.text("singername").ifEmpty { if (" - " in filename) filename.substringBefore(" - ") else "" }.split("、").filter { it.isNotBlank() } }
        val ms = song.number("timelen").takeIf { it > 0 } ?: song.number("timeLen").takeIf { it > 0 }
            ?: song.number("duration")*1000
        val cover = song.text("imgurl").ifEmpty { song.text("album_sizable_cover").ifEmpty { song.obj("trans_param")?.text("union_cover").orEmpty() } }
        return NeteaseTrack(MusicSources.stableId(source,hash),cleanSourceText(title),names.map(::cleanSourceText),ms,
            KugouCatalog.image(cover),source,hash,hash,value("album_id", "albumId", "AlbumID").ifEmpty { album.text("album_id").ifEmpty { album.text("albumId") } }).apply {
            albumAudioId = value("album_audio_id", "albumAudioId").ifEmpty { song.obj("base")?.text("album_audio_id").orEmpty() }
            if (ids.size == names.size) artistIds = ids.map { id -> id.takeIf { it.matches(Regex("[0-9]{1,20}")) && it != "0" }.orEmpty() }
        }
    }

    override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? {
        require(track.source == source && track.nativeId.matches(Regex("[A-Fa-f0-9]{32}")))
        val account = NativeAccounts.get(source)
        if (track.cloud) return KugouCloud().candidates(track, account
            ?: throw SourceAccessException("Log in to Kugou to play your cloud uploads.")).firstOrNull()
        if (account != null) {
            // 登录了还拿不到（新接口偶尔把能放的歌标成不可用）：最后再试一次旧的手机接口
            return accountUrl(track, account) ?: guestUrl(track)?.also {
                me.sorihiko.dango.DangoLogger.info("Kugou: logged-in URL unavailable, using the mobile fallback")
            }
        }
        return guestUrl(track)
    }

    class LoadedAudio(val info: NeteaseApi.SongUrl, val bytes: ByteArray)

    /** 在加载线程逐个验证地址；只保留一份试听和最终可用的完整音频。 */
    internal fun loadAudio(track: NeteaseTrack, active: () -> Boolean): LoadedAudio? {
        val loaded = HashMap<String, ByteArray>()
        var unsupported = ""
        val seen = HashSet<String>()
        fun accept(info: NeteaseApi.SongUrl): Boolean {
            if (!active()) throw java.util.concurrent.CancellationException()
            if (!seen.add(info.url)) return false
            val bytes = try { SourceHttp.bytes(info.url, REFERER) } catch (_: java.io.IOException) { null }
            if (!active()) throw java.util.concurrent.CancellationException()
            if (bytes == null || (!track.cloud && bytes.size < 10_000) || !Mp3Probe.decodable(bytes)) {
                val kind = bytes?.let { Mp3Probe.kind(it) } ?: "download-failed"
                if (kind == "ogg-opus") unsupported = "Ogg Opus audio is not supported yet."
                else if (kind == "ogg" && unsupported.isEmpty()) unsupported = "Unrecognized Ogg audio encoding."
                else if (track.cloud && kind in setOf("mp4", "riff", "unknown"))
                    unsupported = "This cloud audio format is not supported. Only MP3, Ogg Vorbis and FLAC are supported."
                me.sorihiko.dango.DangoLogger.warn("Kugou audio rejected: format={}", kind)
                return false
            }
            me.sorihiko.dango.DangoLogger.info("Kugou audio accepted: format={}", Mp3Probe.kind(bytes))
            loaded[info.url] = bytes
            return true
        }
        val account = NativeAccounts.get(source)
        if (track.cloud) {
            val owner = account ?: throw SourceAccessException("Log in to Kugou to play your cloud uploads.")
            for (candidate in KugouCloud().candidates(track, owner)) {
                if (accept(candidate)) return LoadedAudio(candidate, loaded.getValue(candidate.url))
            }
            throw SourceAccessException(if (unsupported.isNotEmpty()) unsupported else "No playable cloud audio was returned. Check this upload in the official Kugou client.")
        }
        val info = if (account != null && !account.concept) KugouPlayback(accept = ::accept).resolve(track, account)
            else account?.let { accountUrl(track, it) }?.takeIf(::accept)
        if (info != null) return LoadedAudio(info, loaded.getValue(info.url))
        // 游客接口也可能给多个 CDN 地址；主地址无效时仍尝试备用地址。
        val root = guestInfo(track)
        for (candidate in KugouPlayback().candidates(root, track)) {
            if (accept(candidate)) return LoadedAudio(candidate, loaded.getValue(candidate.url))
        }
        if (unsupported.isNotEmpty()) throw SourceAccessException(unsupported)
        return null
    }

    private fun accountUrl(track: NeteaseTrack, account: NativeAccount): NeteaseApi.SongUrl? {
        if (account.concept) return KugouLite.songUrl(track, account)
        return KugouPlayback().resolve(track, account)
    }

    /** 不用登录的手机接口（会员歌只给试听片段） */
    private fun guestUrl(track: NeteaseTrack): NeteaseApi.SongUrl? = KugouPlayback().parse(guestInfo(track), track)

    private fun guestInfo(track: NeteaseTrack): JsonObject {
        val data = SourceHttp.json("https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=${track.nativeId}", REFERER)
        if (track.coverUrl.isEmpty()) {
            track.coverUrl = data.text("album_img").ifEmpty { data.text("imgUrl") }.replace("{size}", "300")
        }
        return data
    }

    override fun lyrics(track: NeteaseTrack): Lyrics? {
        val search = SourceHttp.json("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=${SourceHttp.enc(track.nativeId)}&duration=${track.durationMs}", REFERER)
        val candidate = search.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject ?: return null
        try {
            val krc = SourceHttp.json("https://lyrics.kugou.com/download?ver=1&client=pc&fmt=krc&charset=utf8&id=${SourceHttp.enc(candidate.text("id"))}&accesskey=${SourceHttp.enc(candidate.text("accesskey"))}", REFERER)
            val lines = KugouLyrics.decode(krc.text("content"))
            if (lines.isNotEmpty()) return Lyrics(lines,source.label)
        } catch (_: Exception) { /* Not every recording has KRC; retain the line-timed fallback. */ }
        val root = SourceHttp.json("https://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8&id=${SourceHttp.enc(candidate.text("id"))}&accesskey=${SourceHttp.enc(candidate.text("accesskey"))}", REFERER)
        val text = String(Base64.getDecoder().decode(root.text("content")), Charsets.UTF_8)
        val lines = LyricsProvider.parseLrc(text)
        return if (lines.isEmpty()) null else Lyrics(lines, source.label)
    }
}
