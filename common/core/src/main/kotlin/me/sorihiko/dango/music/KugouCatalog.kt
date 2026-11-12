package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap

/** User-local cloud IDs and public rank IDs are separate namespaces, like the QQ catalog. */
internal class KugouCatalog(
    private val call: (String, Map<String,String>, JsonObject?, NativeAccount?) -> JsonObject = KugouGateway::request,
    private val homepage: () -> String = {
        SourceHttp.bytes("https://www.kugou.com/", "https://www.kugou.com/",4*1024*1024)
            ?.let { String(it,Charsets.UTF_8) } ?: throw SourceAccessException("Kugou recommendations are unavailable. Please retry.")
    },
) {
    /** [editable] = 自己建的普通歌单（不是"我喜欢"，也不是收藏别人的），能往里加歌 */
    private class Ref(val kind: String, val native: Long, val owner: String = "", val editable: Boolean = false, val liked: Boolean = false)
    private val refs = ConcurrentHashMap<Long, Ref>()
    private val recommendations = ConcurrentHashMap<Long,List<NeteaseTrack>>()

    fun playlists(): List<NeteasePlaylist> {
        val account = NativeAccounts.get(MusicSource.KUGOU) ?: return emptyList()
        val output = LinkedHashMap<Long,NeteasePlaylist>()
        val liked = HashSet<Long>()
        for (page in 1..200) {
            val data = call("/cloudlist.service/v8/get_all_list", mapOf("plat" to "1"), JsonObject().apply {
                addProperty("userid",account.userId.toLong()); addProperty("token",account.token)
                addProperty("total_ver",0); addProperty("type",2); addProperty("page",page); addProperty("pagesize",50)
            }, account)
            val list = data.array("info")
            var added = 0
            for (item in list) {
                val row = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                if (row.number("is_del") == 1L) continue
                val local = row.number("listid").takeIf { it > 0 } ?: continue
                val id = MusicSources.stableId(MusicSource.KUGOU,"user:${account.userId}:$local")
                val isLiked = row.number("is_def") == 2L
                refs[id] = Ref("personal",local,account.userId, row.number("type") == 0L && !isLiked, isLiked)
                if (isLiked) liked += id
                val entry = NeteasePlaylist(id,cleanSourceText(row.text("name")),row.number("count").toInt(),
                    image(row.text("pic")), row.number("type") == 0L)
                if (output.put(id,entry) == null) added++
            }
            val total = data.number("list_count")+data.number("collect_count")
            if (list.size() == 0 || (total > 0 && output.size >= total) || (total <= 0 && list.size() < 50))
                return output.values.sortedWith(compareBy<NeteasePlaylist> { if (it.id in liked) 0 else if (it.owned) 1 else 2 })
            if (added == 0) throw SourceAccessException("Kugou repeated a library page. Please retry.")
        }
        throw SourceAccessException("Kugou returned too many library pages. Please retry.")
    }

    /** 自己建的歌单（不含"我喜欢"） */
    fun editable(): List<NeteasePlaylist> = playlists().filter { refs[it.id]?.editable == true }

    /** 往自建歌单里加一首（云歌单接口）；酷狗不告诉我们是不是本来就在里面，成功就当加上了 */
    fun add(playlist: NeteasePlaylist, track: NeteaseTrack): Boolean {
        val account = NativeAccounts.get(MusicSource.KUGOU) ?: throw SourceAccessException("Log in to Kugou first.")
        val ref = refs[playlist.id]?.takeIf { it.editable && it.owner == account.userId }
            ?: throw SourceAccessException("Please reopen the playlist list and try again.")
        addSong(account, ref.native, track)
        return true
    }

    private fun addSong(account: NativeAccount, listid: Long, track: NeteaseTrack) {
        val name = if (track.artists.isEmpty()) track.name else track.artists.joinToString("、") + " - " + track.name
        val song = JsonObject().apply {
            addProperty("number", 1); addProperty("name", name); addProperty("hash", track.nativeId)
            addProperty("size", 0); addProperty("sort", 0); addProperty("timelen", 0); addProperty("bitrate", 0)
            addProperty("album_id", track.albumId.toLongOrNull() ?: 0L)
            addProperty("mixsongid", track.albumAudioId.toLongOrNull() ?: 0L)
        }
        call("/cloudlist.service/v6/add_song",
            mapOf("last_time" to (System.currentTimeMillis() / 1000).toString(), "last_area" to "gztx"),
            JsonObject().apply {
                addProperty("userid", account.userId.toLong()); addProperty("token", account.token)
                addProperty("listid", listid); addProperty("list_ver", 0); addProperty("type", 0)
                addProperty("slow_upload", 1); addProperty("scene", "false;null")
                add("data", com.google.gson.JsonArray().apply { add(song) })
            }, account)
    }

    // ---------------------------------------------------------------- 红心 = "我喜欢"歌单（is_def 2）

    /** "我喜欢"里每首歌的 hash → 它在歌单里的条目 ID（删歌要用这个）；10 分钟内不重读 */
    @Volatile private var likedFiles: Map<String, Long>? = null
    @Volatile private var likedLoadedAt = 0L
    @Volatile private var likedOwner = ""
    /** 本地刚点亮、还没拿到条目 ID 的（不等重读就能显示成红心） */
    private val likedPending = ConcurrentHashMap.newKeySet<String>()

    private fun likedList(account: NativeAccount): Long {
        refs.values.firstOrNull { it.liked && it.owner == account.userId }?.let { return it.native }
        playlists()
        return refs.values.firstOrNull { it.liked && it.owner == account.userId }?.native
            ?: throw SourceAccessException("Kugou liked songs are unavailable. Please retry.")
    }

    private fun loadLikedFiles(account: NativeAccount, force: Boolean = false): Map<String, Long> {
        val cached = likedFiles
        if (!force && cached != null && likedOwner == account.userId && System.currentTimeMillis() - likedLoadedAt < 10 * 60_000L) return cached
        val listid = likedList(account)
        val out = HashMap<String, Long>()
        for (number in 1..200) {
            val data = call("/cloudlist.service/v4/get_list_all_file", mapOf("plat" to "1"), JsonObject().apply {
                addProperty("userid", account.userId.toLong()); addProperty("token", account.token); addProperty("listid", listid)
                addProperty("area_code", 1); addProperty("show_relate_goods", 0); addProperty("allplatform", 1); addProperty("show_cover", 0)
                addProperty("type", 0); addProperty("page", number); addProperty("pagesize", 50)
            }, account)
            val list = data.array("info")
            for (e in list) {
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val hash = o.text("hash").uppercase(java.util.Locale.ROOT)
                if (hash.isNotEmpty()) out[hash] = o.number("fileid")
            }
            val total = data.number("count")
            if (list.size() < 50 || (total > 0 && out.size >= total)) break
        }
        likedFiles = out
        likedOwner = account.userId
        likedLoadedAt = System.currentTimeMillis()
        likedPending.removeAll(out.keys)
        return out
    }

    fun likedState(track: NeteaseTrack): Boolean {
        val account = NativeAccounts.get(MusicSource.KUGOU) ?: return false
        val hash = track.nativeId.uppercase(java.util.Locale.ROOT)
        return hash in likedPending || loadLikedFiles(account).containsKey(hash)
    }

    fun setLiked(track: NeteaseTrack, like: Boolean) {
        val account = NativeAccounts.get(MusicSource.KUGOU) ?: throw SourceAccessException("Log in to Kugou first.")
        val hash = track.nativeId.uppercase(java.util.Locale.ROOT)
        val listid = likedList(account)
        if (like) {
            addSong(account, listid, track)
            likedPending += hash
            return
        }
        // 删歌要条目 ID：缓存里没有（比如刚点亮的）就重读一次
        val fileid = loadLikedFiles(account)[hash]?.takeIf { it > 0 } ?: loadLikedFiles(account, true)[hash]?.takeIf { it > 0 }
        if (fileid == null) { likedPending -= hash; return }
        call("/cloudlist.service/v4/delete_songs", emptyMap(), JsonObject().apply {
            addProperty("listid", listid); addProperty("userid", account.userId.toLong())
            add("data", com.google.gson.JsonArray().apply { add(JsonObject().apply { addProperty("fileid", fileid) }) })
            addProperty("type", 0); addProperty("token", account.token); addProperty("list_ver", 0)
        }, account)
        likedPending -= hash
        likedFiles = likedFiles?.minus(hash)
    }

    fun charts(): List<NeteasePlaylist> {
        val data = call("/ocean/v6/rank/list",mapOf("plat" to "2","withsong" to "1","parentid" to "0"),null,null)
        return data.array("info").mapNotNull { item ->
            val row = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val native = row.number("rankid").takeIf { it > 0 } ?: return@mapNotNull null
            val id = MusicSources.stableId(MusicSource.KUGOU,"rank:$native")
            refs[id] = Ref("rank",native)
            NeteasePlaylist(id,cleanSourceText(row.text("rankname")),row.number("count").toInt(),image(row.text("imgurl")),false)
        }.distinctBy { it.id }
    }

    /** Official homepage's new releases are public curated recommendations, not private daily picks. */
    fun recommend(): List<NeteasePlaylist> {
        val document = homepage()
        val section = Regex("<!--\\s*新歌首发新\\s*-->([\\s\\S]*?)(?=<!--|$)").find(document)?.groupValues?.get(1)
            ?: throw SourceAccessException("Kugou recommendations are unavailable. Please retry.")
        val tracks = LinkedHashMap<String,NeteaseTrack>()
        for (match in Regex("dataobj=['\"]([^'\"]*)['\"]").findAll(section)) {
            val value = entities(match.groupValues[1])
            val list = try { JsonParser().parse(value).takeIf { it.isJsonArray }?.asJsonArray } catch (_: Exception) { null } ?: continue
            for (item in list) if (item.isJsonObject) KugouMusicSource.parseTrack(item.asJsonObject)?.let { tracks[it.key] = it }
        }
        if (tracks.isEmpty()) throw SourceAccessException("Kugou recommendations are unavailable. Please retry.")
        val id = MusicSources.stableId(MusicSource.KUGOU,"homepage:new-releases")
        refs[id] = Ref("home",0)
        recommendations[id] = tracks.values.toList()
        val cover = Regex("_src=['\"]([^'\"]+)['\"]").find(section)?.groupValues?.get(1).orEmpty()
        return listOf(NeteasePlaylist(id,"酷狗新歌首发",tracks.size,image(cover),false))
    }

    fun tracks(id: Long): List<NeteaseTrack> {
        val ref = refs[id] ?: throw SourceAccessException("Please reopen the playlist and try again.")
        if (ref.kind == "home") return recommendations[id] ?: emptyList()
        val account = NativeAccounts.get(MusicSource.KUGOU)
        if (ref.kind == "personal" && account?.userId != ref.owner)
            throw SourceAccessException("Log in to Kugou to read your playlists.")
        fun page(number: Int): JsonObject = if (ref.kind == "rank") call("/ocean/v6/rank/song",
            mapOf("rankid" to ref.native.toString(),"page" to number.toString(),"pagesize" to "50"),null,null)
        else call("/cloudlist.service/v4/get_list_all_file",mapOf("plat" to "1"),JsonObject().apply {
            addProperty("userid",account!!.userId.toLong()); addProperty("token",account.token); addProperty("listid",ref.native)
            addProperty("area_code",1); addProperty("show_relate_goods",0); addProperty("allplatform",1); addProperty("show_cover",1)
            addProperty("type",0); addProperty("page",number); addProperty("pagesize",50)
        },account)
        // 个人歌单每首歌带一个 sort：酷狗 App 里的顺序（用户拖动排过的也是它），从小到大就是 App 里从上到下
        val sortOf = HashMap<String, Long>()
        fun parse(data: JsonObject): List<NeteaseTrack> =
            data.array("info").mapNotNull { e ->
                if (!e.isJsonObject) return@mapNotNull null
                val o = e.asJsonObject
                KugouMusicSource.parseTrack(o)?.also { t ->
                    if (ref.kind == "personal" && o.has("sort")) o.text("sort").toLongOrNull()?.let { sortOf.putIfAbsent(t.key, it) }
                }
            }
        /** 每首都有 sort 时按它排（和 App 一样）；有缺的就保持接口给的顺序 */
        fun appOrder(list: List<NeteaseTrack>): List<NeteaseTrack> =
            if (list.isNotEmpty() && list.all { sortOf.containsKey(it.key) }) list.sortedBy { sortOf.getValue(it.key) } else list

        val tracks = LinkedHashMap<String,NeteaseTrack>()
        val first = page(1)
        val total = first.number(if (ref.kind == "rank") "total" else "count")
        val firstPage = parse(first)
        if (ref.kind == "personal") {
            // 个人歌单接口按收藏时间从早到晚返回，酷狗 App 里是最新收藏的在最前面：
            // 先拿到总数，再从最后一页往前取，每页倒过来接在后面，边取边显示时顺序就已经是最终的样子
            // 页数按服务器第一页实际给了几条算（有时一页不到 50 条）
            val perPage = first.array("info").size().coerceAtLeast(1)
            val pages = if (total > perPage) ((total + perPage - 1) / perPage).toInt() else 1
            if (pages > 400) throw SourceAccessException("Kugou returned too many library pages. Please retry.")
            var received = first.array("info").size().toLong()
            for (number in pages downTo 2) {
                val data = page(number)
                received += data.array("info").size()
                for (t in parse(data).asReversed()) tracks.putIfAbsent(t.key, t)
                TrackLoadProgress.report(tracks.size, total.toInt())
                TrackLoadProgress.partial(tracks.values.toList())
            }
            for (t in firstPage.asReversed()) tracks.putIfAbsent(t.key, t)
            if (total <= 0 || received >= total) {
                TrackLoadProgress.report(tracks.size, total.toInt())
                return appOrder(tracks.values.toList())
            }
            // 每页条数不一样、倒着取漏了：退回从前往后一页页翻，直到收齐
            val forward = LinkedHashMap<String,NeteaseTrack>()
            for (t in firstPage) forward.putIfAbsent(t.key, t)
            var consumed = first.array("info").size().toLong()
            for (number in 2..400) {
                val data = page(number)
                val size = data.array("info").size()
                if (size == 0) break
                var added = 0
                for (t in parse(data)) if (forward.putIfAbsent(t.key, t) == null) added++
                consumed += size
                TrackLoadProgress.report(forward.size, total.toInt())
                if (consumed >= total) break
                if (added == 0) throw SourceAccessException("Kugou repeated a library page. Please retry.")
            }
            return appOrder(forward.values.toList().asReversed().toList())
        }
        // 排行榜：从前往后翻页
        var list = firstPage
        var received = first.array("info").size().toLong()
        for (number in 1..400) {
            if (number > 1) {
                val data = page(number)
                if (data.array("info").size() == 0) return tracks.values.toList()
                list = parse(data)
                received += data.array("info").size()
            }
            var added = 0
            for (t in list) if (tracks.put(t.key, t) == null) added++
            TrackLoadProgress.report(tracks.size, total.toInt())
            if ((total > 0 && received >= total) || (total <= 0 && list.size < 50)) return tracks.values.toList()
            if (added == 0) throw SourceAccessException("Kugou repeated a library page. Please retry.")
            TrackLoadProgress.partial(tracks.values.toList())
        }
        throw SourceAccessException("Kugou returned too many library pages. Please retry.")
    }

    companion object {
        internal fun image(raw: String): String {
            val value = raw.replace("{size}","300").trim()
            val url = if (value.startsWith("//")) "https:$value" else if (value.startsWith("http://")) "https:"+value.substring(5) else value
            return try { val uri=java.net.URI(url); if (uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null) url else "" } catch (_: Exception) { "" }
        }
        private fun entities(value: String) = value.replace("&#34;","\"").replace("&quot;","\"").replace("&#39;","'")
            .replace("&apos;","'").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&")
    }
}
