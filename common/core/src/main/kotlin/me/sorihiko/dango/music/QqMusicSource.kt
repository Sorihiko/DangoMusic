package me.sorihiko.dango.music

import com.google.gson.JsonObject
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider

/** Native QQ Music client. MP3 only: the shared player uses JLayer. */
object QqMusicSource : MusicSourceApi {
    override val source = MusicSource.QQ
    private const val REFERER = "https://y.qq.com/"
    private val catalog = QqCatalog(::cgi, ::profileId)
    override val libraryWarning get() = catalog.warning
    override fun playlists() = catalog.playlists()
    override fun recommend() = catalog.recommend()
    override fun charts() = catalog.charts()
    override fun playlistTracks(id: Long) = if (id == CloudDrives.QQ_ID) {
        val account = NativeAccounts.get(source) ?: throw SourceAccessException("Log in to QQ Music to read your cloud drive.")
        QqCloud().tracks(account)
    } else QqArtists.albumTracksFor(id) ?: catalog.tracks(id)

    override val hasArtists get() = true
    override fun artist(id: String) = if (id.matches(Regex("[A-Za-z0-9]{1,64}"))) QqArtists.artist(id) else null
    override fun artistAlbums(id: String) = if (id.matches(Regex("[A-Za-z0-9]{1,64}"))) QqArtists.artistAlbums(id) else emptyList()
    override fun findArtist(name: String) = QqArtists.findArtist(name)

    internal fun profileId(account: NativeAccount): String {
        try {
            val profile = cgi("music.UserInfo.userInfoServer", "GetLoginUserInfo", JsonObject(), account)
            for (block in listOfNotNull(profile.obj("info"), profile.obj("info")?.obj("base"), profile)) {
                for (field in listOf("encrypt_uin", "encryptUin", "encryptedUin")) block.text(field).takeIf { it.isNotEmpty() }?.let { return it }
            }
        } catch (_: Exception) { /* Older account profiles can resolve their public ID via the homepage. */ }
        // The public c6 route resolves numeric IDs; c.y's older route requires a different web session.
        // No login cookies are necessary for this public metadata lookup.
        val data = SourceHttp.json(
            "https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?format=json&cid=205360838&ct=20&cv=4747474&userid=${account.userId}", REFERER)
        return data.obj("data")?.obj("creator")?.text("encrypt_uin")?.takeIf { it.isNotEmpty() }
            ?: throw SourceAccessException("QQ Music profile is unavailable. Please retry or log in again.")
    }

    internal fun cgi(module: String, method: String, params: JsonObject, account: NativeAccount? = NativeAccounts.get(source)): JsonObject {
        val payload = JsonObject().apply {
            add("comm", JsonObject().apply {
                addProperty("ct", 19); addProperty("cv", 2201)
                if (account != null) {
                    val key = NativeAccounts.cookie(account.cookies, "qqmusic_key")
                    addProperty("uin", account.userId); addProperty("authst", key)
                    addProperty("g_tk", NativeAccounts.hash33(key)); addProperty("tmeLoginType", account.loginType)
                }
            })
            add("req_0", JsonObject().apply { addProperty("module", module); addProperty("method", method); add("param", params) })
        }
        val root = AuthHttp.request(source, "https://u.y.qq.com/cgi-bin/musicu.fcg",
            account?.cookies ?: java.net.CookieManager(), payload.toString()).json()
        val block = root.obj("req_0")
        if (!root.has("code") || root.number("code") != 0L || block == null || !block.has("code") || block.number("code") != 0L)
            throw SourceAccessException("QQ Music request failed. Please retry or log in again.")
        val data = block.obj("data") ?: throw SourceAccessException("QQ Music returned an incomplete response. Please retry.")
        if (data.has("code") && data.number("code") != 0L) throw SourceAccessException("QQ Music request failed. Please retry or log in again.")
        return data
    }

    private fun json(url: String): JsonObject {
        val account = NativeAccounts.get(source)
        return if (account == null) SourceHttp.json(url, REFERER) else AuthHttp.request(source, url, account.cookies).json()
    }

    override fun search(query: String): List<NeteaseTrack> {
        val response = json("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&p=1&n=40&w=${SourceHttp.enc(query)}")
        check(response.number("code") == 0L) { "QQ search rejected" }
        return parseSearch(response)
    }

    internal fun parseSearch(response: JsonObject): List<NeteaseTrack> {
        val list = response.getAsJsonObject("data")?.getAsJsonObject("song")?.getAsJsonArray("list") ?: return emptyList()
        return list.mapNotNull { entry -> parseTrack(entry.asJsonObject) }
    }

    internal fun parseTrack(song: JsonObject): NeteaseTrack? {
        val mid = song.text("songmid").ifEmpty { song.text("mid") }
        if (!mid.matches(Regex("[A-Za-z0-9]{1,64}"))) return null
        val album = song.text("albummid").ifEmpty { song.obj("album")?.text("mid").orEmpty() }
        return NeteaseTrack(
                MusicSources.stableId(source, mid), cleanSourceText(song.text("songname").ifEmpty { song.text("title").ifEmpty { song.text("name") } }),
                song.getAsJsonArray("singer")?.map { cleanSourceText(it.asJsonObject.text("name")) } ?: emptyList(),
                song.number("interval") * 1000,
                if (album.isEmpty()) "" else "https://y.gtimg.cn/music/photo_new/T002R300x300M000$album.jpg",
                source, mid, song.text("strMediaMid").ifEmpty { song.text("media_mid").ifEmpty { song.obj("file")?.text("media_mid").orEmpty().ifEmpty { mid } } }, album,
        ).apply {
            artistIds = song.getAsJsonArray("singer")?.map { if (it.isJsonObject) it.asJsonObject.text("mid") else "" } ?: emptyList()
        }
    }

    override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? =
        QqPlayback().resolve(track, NativeAccounts.get(source))

    override fun lyrics(track: NeteaseTrack): Lyrics? {
        try {
            val data = cgi("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", JsonObject().apply {
                addProperty("songMid", track.nativeId); addProperty("type", 1)
                for (key in listOf("lrc_t", "qrc_t", "roma", "roma_t", "trans_t")) addProperty(key, 0)
                addProperty("trans", 1)
                addProperty("crypt", 1); addProperty("qrc", 1)
            })
            val lines = QqLyrics.decode(data.text("lyric"))
            if (lines.isNotEmpty()) return Lyrics(LyricsProvider.withTranslation(lines, translation(data.text("trans"))), source.label)
        } catch (_: Exception) { /* Songs without QRC still use the ordinary line-timed lyric endpoint. */ }
        val root = json("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&songmid=${SourceHttp.enc(track.nativeId)}")
        val lines = LyricsProvider.parseLrc(root.text("lyric"))
        return if (lines.isEmpty()) null else Lyrics(LyricsProvider.withTranslation(lines, translation(root.text("trans"))), source.label)
    }

    /** QQ 的翻译是普通 LRC（PlayLyricInfo 里和 QRC 一样加了密），解不出来就当没有 */
    private fun translation(value: String): List<me.sorihiko.dango.lyrics.LyricLine> = try {
        if (value.isBlank()) emptyList() else LyricsProvider.parseLrc(QqLyrics.plaintext(value))
    } catch (_: Exception) {
        emptyList()
    }
}
