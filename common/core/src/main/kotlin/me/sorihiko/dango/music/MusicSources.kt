package me.sorihiko.dango.music

import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Persistent provider names; never use a process-local counter as a song identifier. */
enum class MusicSource(val key: String, val label: String) {
    NETEASE("netease", "NetEase"), QQ("qq", "QQ Music"), KUGOU("kugou", "Kugou");
}

/** Only locally-authored public messages belong here; never include a token or request URL. */
class SourceAccessException(message: String) : IllegalStateException(message)

interface MusicSourceApi {
    val source: MusicSource
    val libraryWarning: String get() = ""
    fun search(query: String): List<NeteaseTrack>
    fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl?
    fun lyrics(track: NeteaseTrack): Lyrics?
    fun playlists(): List<NeteasePlaylist> = emptyList()
    fun recommend(): List<NeteasePlaylist> = emptyList()
    fun charts(): List<NeteasePlaylist> = emptyList()
    fun playlistTracks(id: Long): List<NeteaseTrack> = emptyList()

    /** 有没有歌手页 */
    val hasArtists: Boolean get() = false
    /** 歌手信息和热门歌曲；[id] 是音源自己的歌手 ID */
    fun artist(id: String): SourceArtist? = null
    /** 歌手的专辑，当成歌单列出来（点开走 [playlistTracks]） */
    fun artistAlbums(id: String): List<NeteasePlaylist> = emptyList()
    /** 按名字找歌手 ID，找不到返回 "" */
    fun findArtist(name: String): String = ""
}

object MusicSources {
    @Volatile var selected = MusicSource.NETEASE
    private val netease = object : MusicSourceApi {
        override val source = MusicSource.NETEASE
        override fun search(query: String) = NeteaseApi.search(query)
        override fun songUrl(track: NeteaseTrack) = NeteaseApi.songUrl(track)
        override fun lyrics(track: NeteaseTrack) = NeteaseApi.lyrics(track)
        override fun playlists(): List<NeteasePlaylist> {
            NeteaseApi.ensureCookieFile()
            val lists = NeteaseApi.userPlaylists()
            return if (NeteaseApi.loggedIn) lists + NeteasePlaylist(NeteaseApi.CLOUD_ID, me.sorihiko.dango.i18n.I18n.text("Cloud drive"), 0, "", false) else lists
        }
        override fun recommend(): List<NeteasePlaylist> {
            // 登录了就在最前面放"每日推荐""私人 FM"（拿不到不影响下面的推荐歌单）
            val personal = try { NeteaseApi.personalCards() } catch (e: Exception) {
                me.sorihiko.dango.DangoLogger.warn("NetEase daily recommend failed: {}", e.toString())
                emptyList()
            }
            return personal + NeteaseApi.recommendPlaylists()
        }
        override fun charts() = NeteaseApi.toplists()
        override fun playlistTracks(id: Long) = when (id) {
            NeteaseApi.DAILY_ID -> NeteaseApi.dailySongs()
            NeteaseApi.FM_ID -> NeteaseApi.personalFm()
            NeteaseApi.CLOUD_ID -> NeteaseApi.cloudTracks()
            // 歌手页里点开的专辑
            else -> NeteaseApi.albumIdOf(id)?.let { NeteaseApi.albumTracks(it) } ?: NeteaseApi.playlistTracks(id)
        }
        override val hasArtists get() = true
        override fun artist(id: String) = id.toLongOrNull()?.let { NeteaseApi.artist(it) }
        override fun artistAlbums(id: String) = id.toLongOrNull()?.let { NeteaseApi.artistAlbums(it) } ?: emptyList()
        override fun findArtist(name: String) = NeteaseApi.findArtist(name).takeIf { it > 0 }?.toString().orEmpty()
    }

    fun api(source: MusicSource = selected): MusicSourceApi = when (source) {
        MusicSource.NETEASE -> netease
        MusicSource.QQ -> QqMusicSource
        MusicSource.KUGOU -> KugouMusicSource
    }

    /** Compatibility ID for existing numeric UI fields. Identity is always [NeteaseTrack.key]. */
    fun stableId(source: MusicSource, nativeId: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest("${source.key}:$nativeId".toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(digest).long or Long.MIN_VALUE
    }
}
