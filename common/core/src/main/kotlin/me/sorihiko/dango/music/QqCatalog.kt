package me.sorihiko.dango.music

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** Read-only QQ Music library. UI IDs keep charts, playlists and private liked lists separate. */
internal class QqCatalog(private val call: (String, String, JsonObject, NativeAccount?) -> JsonObject,
    private val profileId: (NativeAccount) -> String) {
    private class Ref(val id: Long, val chart: Boolean, val dir: Int = 0, val owner: String = "")
    private val refs = ConcurrentHashMap<Long, Ref>()
    @Volatile var warning = ""
        private set
    private fun params(vararg pairs: Pair<String, Any>) = JsonObject().apply {
        for ((key, value) in pairs) when (value) {
            is Number -> addProperty(key, value)
            is Boolean -> addProperty(key, value)
            else -> addProperty(key, value.toString())
        }
    }

    fun recommend(): List<NeteasePlaylist> {
        val data = call("music.playlist.PlaylistSquare", "GetRecommendFeed", params("From" to 0, "Size" to 30), NativeAccounts.get(MusicSource.QQ))
        return data.array("List").mapNotNull { it.asJsonObject.obj("Playlist")?.obj("basic")?.let { basic -> playlist(basic, false) } }
    }

    fun charts(): List<NeteasePlaylist> {
        val data = call("music.musicToplist.Toplist", "GetAll", JsonObject(), null)
        return data.array("group").flatMap { group -> group.asJsonObject.array("toplist").mapNotNull { item ->
            val top = item.asJsonObject
            val native = top.number("topId").takeIf { it > 0 } ?: return@mapNotNull null
            val id = MusicSources.stableId(MusicSource.QQ, "chart:$native")
            refs[id] = Ref(native, true)
            NeteasePlaylist(id, cleanSourceText(top.text("title")), top.number("totalNum").toInt(),
                top.text("frontPicUrl").ifEmpty { top.text("headPicUrl") }, false)
        } }.distinctBy { it.id }
    }

    fun playlists(): List<NeteasePlaylist> {
        warning = ""
        val account = NativeAccounts.get(MusicSource.QQ) ?: return emptyList()
        val created = call("music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", params("uin" to account.userId), account)
        val own = created.array("v_playlist").mapNotNull { playlist(it.asJsonObject, true, account.userId) }.toMutableList()
        // The liked directory may not have a public tid. It is addressed by its owner and dirid=201.
        val likedId = MusicSources.stableId(MusicSource.QQ, "liked:${account.userId}")
        if (own.none { it.id == likedId }) {
            val id = MusicSources.stableId(MusicSource.QQ, "liked:${account.userId}")
            refs[id] = Ref(0, false, 201, account.userId)
            own.add(0, NeteasePlaylist(id, "我喜欢", 0, "", true))
        }
        val favorites = mutableListOf<NeteasePlaylist>()
        val orderedOwn = own.sortedBy { if (it.id == likedId) 0 else 1 }
        val encrypted = try { profileId(account) } catch (_: Exception) {
            warning = "Your playlists loaded, but QQ Music favorites could not be read. Please retry or log in again."
            return orderedOwn
        }
        var offset = 0
        repeat(100) {
            val page = try { call("music.musicasset.PlaylistFavRead", "CgiGetPlaylistFavInfo",
                params("uin" to encrypted, "offset" to offset, "size" to 100), account) } catch (_: Exception) {
                warning = "Your playlists loaded, but QQ Music favorites could not be read. Please retry or log in again."
                return (orderedOwn + favorites).distinctBy { it.id }
            }
            val items = page.array("v_list")
            favorites += items.mapNotNull { playlist(it.asJsonObject, false) }
            offset += items.size()
            if (items.size() == 0 || page.number("hasmore") == 0L) return (orderedOwn + favorites).distinctBy { it.id }
        }
        throw SourceAccessException("QQ Music returned too many playlists. Please retry.")
    }

    private fun playlist(data: JsonObject, owned: Boolean, owner: String = ""): NeteasePlaylist? {
        val tid = data.number("tid").takeIf { it > 0 } ?: data.number("id")
        val dir = (if (data.has("dirId")) data.number("dirId") else data.number("dirid")).toInt()
        if (tid <= 0 && !(owned && dir == 201)) return null
        val liked = owned && dir == 201
        val id = MusicSources.stableId(MusicSource.QQ, if (liked) "liked:$owner" else "playlist:$tid")
        refs[id] = Ref(tid.coerceAtLeast(0), false, if (liked && tid <= 0) 201 else 0, if (owned) owner else "")
        val cover = data.obj("cover")
        return NeteasePlaylist(id, cleanSourceText(data.text("title").ifEmpty { data.text("dirName").ifEmpty { data.text("name").ifEmpty { if (liked) "我喜欢" else "QQ Music" } } }),
            data.number("song_cnt").takeIf { it > 0 }?.toInt() ?: (if (data.has("songNum")) data.number("songNum") else data.number("songnum")).toInt(),
            data.text("picurl").ifEmpty { data.text("picUrl").ifEmpty { data.text("logo").ifEmpty { cover?.text("default_url").orEmpty().ifEmpty { cover?.text("medium_url").orEmpty().ifEmpty { data.text("bigpicUrl") } } } } }, owned)
    }

    fun tracks(id: Long): List<NeteaseTrack> {
        val ref = refs[id] ?: throw SourceAccessException("Please reopen the playlist and try again.")
        val account = NativeAccounts.get(MusicSource.QQ)
        if (ref.owner.isNotEmpty() && account?.userId != ref.owner) throw SourceAccessException("Log in to QQ Music to read your playlists.")
        val songs = LinkedHashMap<String, NeteaseTrack>()
        var offset = 0
        repeat(200) {
            val page = if (ref.chart) call("music.musicToplist.Toplist", "GetDetail",
                params("topId" to ref.id, "offset" to offset, "num" to 100), account)
            else call("music.srfDissInfo.DissInfo", "CgiGetDiss",
                params("disstid" to ref.id, "dirid" to ref.dir, "song_begin" to offset, "song_num" to 100,
                    "userinfo" to true, "tag" to true, "orderlist" to true).apply {
                    if (ref.dir == 201 && account != null) addProperty("enc_host_uin", profileId(account))
                }, account)
            val list = page.array(if (ref.chart) "songInfoList" else "songlist")
            var added = 0
            for (item in list) QqMusicSource.parseTrack(item.asJsonObject)?.let { if (songs.put(it.key, it) == null) added++ }
            offset += list.size()
            val total = if (ref.chart) page.obj("data")?.number("totalNum") ?: 0 else page.number("total_song_num")
            TrackLoadProgress.report(songs.size, total.toInt())
            if (list.size() == 0 || (total > 0 && offset >= total) || (!ref.chart && page.number("hasmore") == 0L) || (ref.chart && list.size() < 100)) return songs.values.toList()
            if (added == 0) throw SourceAccessException("QQ Music repeated a playlist page. Please retry.")
            TrackLoadProgress.partial(songs.values.toList())
        }
        throw SourceAccessException("QQ Music returned too many songs. Please retry.")
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
