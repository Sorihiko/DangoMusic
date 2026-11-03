package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class NeteasePlaylist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val coverUrl: String,
    /** 自己创建的（含"我喜欢的音乐"）还是收藏别人的 */
    val owned: Boolean,
)

/**
 * 网易云音乐接口（内置播放器用）。
 *
 * 登录：把网易云网页版登录后的 Cookie（至少包含 MUSIC_U=...）放进
 *   config/dangomusic/netease_cookie.txt
 * 只保存在本地，只在请求 music.163.com 时带上。
 */
object NeteaseApi {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val cookieFile: File get() = DangoMusic.instance.clientDir.resolve("netease_cookie.txt").toFile()

    @Volatile var nickname: String = ""
        private set
    @Volatile var userId: Long = 0L
        private set
    @Volatile var avatarUrl: String = ""
        private set

    /** 读 cookie 文件；只填了 MUSIC_U 的值也行 */
    private fun cookie(): String {
        val f = cookieFile
        if (!f.isFile) return ""
        var c = String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim()
        if (c.isEmpty()) return ""
        if (!c.contains("=")) c = "MUSIC_U=$c"
        return c
    }

    val loggedIn: Boolean get() = cookie().contains("MUSIC_U=")

    /** 扫码登录成功后保存 Cookie，并清掉缓存的账号信息 */
    fun saveCookie(cookie: String) {
        try {
            val f = cookieFile
            f.parentFile.mkdirs()
            f.writeText(cookie, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            DangoLogger.warn("Save NetEase cookie failed: {}", e.toString())
        }
        resetUser()
    }

    /** 退出登录：清空 Cookie */
    fun logout() {
        saveCookie("")
    }

    fun resetUser() {
        userId = 0L
        nickname = ""
        avatarUrl = ""
        liked = null
        likedAt = 0L
    }

    /** 第一次运行时创建一个带说明的空 cookie 文件，方便用户找到位置 */
    fun ensureCookieFile() {
        val f = cookieFile
        if (f.exists()) return
        try {
            f.parentFile.mkdirs()
            f.writeText("", StandardCharsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- 账号

    /** 拿到当前用户 ID：优先用 cookie 查账号；没登录时退回读网易云客户端本地记录里的 uid */
    fun resolveUser(): Long {
        if (userId != 0L) return userId
        if (loggedIn) {
            try {
                val o = getJson("https://music.163.com/api/nuser/account/get")
                val profile = o?.get("profile") as? JsonObject
                val account = o?.get("account") as? JsonObject
                val id = profile?.get("userId")?.asLong ?: account?.get("id")?.asLong ?: 0L
                if (id != 0L) {
                    userId = id
                    nickname = profile?.get("nickname")?.asString ?: ""
                    avatarUrl = profile?.str("avatarUrl") ?: ""
                    return id
                }
            } catch (e: Exception) {
                DangoLogger.warn("NetEase account lookup failed: {}", e.toString())
            }
        }
        // 网易云客户端本地记录（不需要登录 cookie）
        try {
            val f = File(System.getenv("LOCALAPPDATA") ?: "", "NetEase\\CloudMusic\\webdata\\file\\recentListen")
            if (f.isFile) {
                val o = JsonParser().parse(String(f.readBytes(), StandardCharsets.UTF_8))
                if (o.isJsonObject) {
                    val id = o.asJsonObject.get("uid")?.asString?.toLongOrNull() ?: 0L
                    if (id != 0L) userId = id
                }
            }
        } catch (_: Exception) {
        }
        return userId
    }

    // ---------------------------------------------------------------- 歌单

    fun userPlaylists(): List<NeteasePlaylist> {
        val uid = resolveUser()
        if (uid == 0L) return emptyList()
        val o = getJson("https://music.163.com/api/user/playlist/?uid=$uid&limit=1000&offset=0") ?: return emptyList()
        val arr = o.getAsJsonArray("playlist") ?: return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val p = el.asJsonObject
            val creator = p.get("creator") as? JsonObject
            NeteasePlaylist(
                id = p.get("id")?.asLong ?: return@mapNotNull null,
                name = p.get("name")?.asString ?: "",
                trackCount = p.get("trackCount")?.asInt ?: 0,
                coverUrl = p.get("coverImgUrl")?.asString ?: "",
                owned = creator?.get("userId")?.asLong == uid,
            )
        }
    }

    /** 歌单里的全部歌曲：详情接口给出全部 ID，前一部分直接带歌曲信息，剩下的分批补齐 */
    fun playlistTracks(playlistId: Long): List<NeteaseTrack> {
        // 详情只要前 200 首的完整信息：回包小、来得快，界面能马上先显示；剩下的按 ID 分批补
        val o = getJson("https://music.163.com/api/v6/playlist/detail?id=$playlistId&n=200&s=0") ?: return emptyList()
        val pl = o.get("playlist") as? JsonObject ?: return emptyList()
        val known = LinkedHashMap<Long, NeteaseTrack>()
        pl.getAsJsonArray("tracks")?.forEach { el ->
            if (el.isJsonObject) NeteaseTrack.parse(el.asJsonObject)?.let { known[it.id] = it }
        }
        val ids = pl.getAsJsonArray("trackIds")?.mapNotNull { el ->
            if (el.isJsonObject) el.asJsonObject.get("id")?.asLong else null
        } ?: known.keys.toList()

        val missing = ids.filter { it !in known }
        TrackLoadProgress.report(ids.size - missing.size, ids.size)
        // 先把开头已经齐了的那段交给界面（到第一首还没拿到的为止），后面每补一批就再交一次
        var ready = 0
        fun publishReady() {
            if (!TrackLoadProgress.wantsPartial) return
            while (ready < ids.size && ids[ready] in known) ready++
            if (ready < ids.size) TrackLoadProgress.partial(ids.subList(0, ready).map { known.getValue(it) })
        }
        publishReady()
        for (batch in missing.chunked(200)) {
            try {
                val res = getJson("https://music.163.com/api/song/detail?ids=" + enc(batch.joinToString(",", "[", "]")))
                res?.getAsJsonArray("songs")?.forEach { el ->
                    if (el.isJsonObject) NeteaseTrack.parse(el.asJsonObject)?.let { known[it.id] = it }
                }
            } catch (_: Exception) {
            }
            TrackLoadProgress.report(ids.count { it in known }, ids.size)
            publishReady()
        }
        return ids.mapNotNull { known[it] }
    }

    fun lyrics(track: NeteaseTrack): me.sorihiko.dango.lyrics.Lyrics? = NeteaseLyrics(
        cloud = { id ->
            if (!loggedIn) null else {
                val uid = resolveUser()
                if (uid <= 0) null else NeteasePlayerRequest.cloudLyric(JsonObject().apply {
                    addProperty("userId", uid); addProperty("songId", id)
                    addProperty("lv", -1); addProperty("kv", -1)
                }, cookieHeader())
            }
        },
        catalog = { me.sorihiko.dango.lyrics.LyricsProvider.neteaseLyricById(it) },
        lookup = { current -> cloudDetails(listOf(current.cloudId.takeIf { it > 0 } ?: current.id)).firstOrNull {
            it.id == current.id || it.cloudId == (current.cloudId.takeIf { value -> value > 0 } ?: current.id)
        } },
    ).resolve(track)

    const val CLOUD_ID = -163_003L

    /** 云盘分页保留上传标记；边加载边交给现有歌单界面。 */
    fun cloudTracks(): List<NeteaseTrack> {
        if (!loggedIn) throw SourceAccessException("Log in to NetEase to read your cloud drive.")
        val songs = LinkedHashMap<Long, NeteaseTrack>()
        var offset = 0
        repeat(400) {
            val root = account("https://music.163.com/weapi/v1/cloud/get", JsonObject().apply { addProperty("limit", 200); addProperty("offset", offset) })
                ?: throw SourceAccessException("Unable to load the NetEase cloud drive. Please retry or log in again.")
            val rows = root.array("data")
            var added = 0
            for (row in rows) if (row.isJsonObject) NeteaseTrack.parse(row.asJsonObject)?.let {
                it.cloud = true
                if (songs.put(it.id, it) == null) added++
            }
            TrackLoadProgress.report(songs.size, root.number("count").toInt())
            val more = root.get("hasMore")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: (rows.size() == 200)
            if (!more || rows.size() == 0) return songs.values.toList()
            if (added == 0) throw SourceAccessException("The NetEase cloud drive repeated a page. Please retry.")
            TrackLoadProgress.partial(songs.values.toList())
            offset += rows.size()
        }
        throw SourceAccessException("The NetEase cloud drive returned too many pages.")
    }

    fun cloudDetails(ids: List<Long>): List<NeteaseTrack> {
        if (!loggedIn || ids.isEmpty()) return emptyList()
        val results = ArrayList<NeteaseTrack>()
        for (batch in ids.chunked(200)) {
            val root = account("https://music.163.com/weapi/v1/cloud/get/byids", JsonObject().apply {
                add("songIds", com.google.gson.JsonArray().apply { batch.forEach { add(com.google.gson.JsonPrimitive(it.toString())) } })
            }) ?: throw SourceAccessException("Unable to load the NetEase cloud drive. Please retry or log in again.")
            results += tracksOf(root.getAsJsonArray("data")).onEach { it.cloud = true }
        }
        return results
    }

    /** 首页推荐歌单（不需要登录） */
    fun recommendPlaylists(): List<NeteasePlaylist> {
        val o = getJson("https://music.163.com/api/personalized/playlist?limit=30") ?: return emptyList()
        val arr = o.getAsJsonArray("result") ?: return emptyList()
        return arr.mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            NeteasePlaylist(
                id = p.get("id")?.asLong ?: return@mapNotNull null,
                name = p.str("name"),
                trackCount = p.get("trackCount")?.asInt ?: 0,
                coverUrl = p.str("picUrl"),
                owned = false,
            )
        }
    }

    /** 排行榜（飙升榜、新歌榜、热歌榜……） */
    fun toplists(): List<NeteasePlaylist> {
        val o = getJson("https://music.163.com/api/toplist") ?: return emptyList()
        val arr = o.getAsJsonArray("list") ?: return emptyList()
        return arr.mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            NeteasePlaylist(
                id = p.get("id")?.asLong ?: return@mapNotNull null,
                name = p.str("name"),
                trackCount = p.get("trackCount")?.asInt ?: 0,
                coverUrl = p.str("coverImgUrl"),
                owned = false,
            )
        }
    }

    // ---------------------------------------------------------------- 每日推荐 / 私人 FM / 红心（都要登录）

    /** 首页上"每日推荐""私人 FM"两张卡片用的歌单 ID（负数，不会和真的歌单撞） */
    const val DAILY_ID = -163_001L
    const val FM_ID = -163_002L

    /** 带登录信息的 weapi 请求；返回整个回包（code 不是 200 时为 null） */
    private fun account(url: String, body: JsonObject): JsonObject? {
        if (!loggedIn) return null
        body.addProperty("csrf_token", csrf())
        val res = NeteaseLogin.weapi(url, body.toString(), cookieHeader()) ?: return null
        val root = JsonParser().parse(res.first)
        val o = if (root.isJsonObject) root.asJsonObject else return null
        if (o.get("code")?.takeIf { it.isJsonPrimitive }?.asInt != 200) {
            DangoLogger.warn("NetEase {} failed: code {}", url.substringAfter("/weapi/").substringBefore('?'), codeOf(o))
            return null
        }
        return o
    }

    private fun csrf() = cookie().split(';').map { it.trim() }.firstOrNull { it.startsWith("__csrf=") }?.substringAfter('=').orEmpty()

    private fun tracksOf(arr: com.google.gson.JsonArray?): List<NeteaseTrack> =
        arr?.mapNotNull { if (it.isJsonObject) NeteaseTrack.parse(it.asJsonObject) else null } ?: emptyList()

    /** 每日推荐的 30 首（每天早上 6 点换） */
    fun dailySongs(): List<NeteaseTrack> {
        val o = account("https://music.163.com/weapi/v3/discovery/recommend/songs", JsonObject()) ?: return emptyList()
        return tracksOf((o.get("data") as? JsonObject)?.getAsJsonArray("dailySongs"))
    }

    /** 私人 FM：每次给几首，放完再要 */
    fun personalFm(): List<NeteaseTrack> {
        val o = account("https://music.163.com/weapi/v1/radio/get", JsonObject()) ?: return emptyList()
        return tracksOf(o.getAsJsonArray("data"))
    }

    /** 首页的两张卡片（没登录时没有） */
    fun personalCards(): List<NeteasePlaylist> {
        if (!loggedIn) return emptyList()
        val daily = dailySongs()
        val cards = ArrayList<NeteasePlaylist>()
        if (daily.isNotEmpty()) cards += NeteasePlaylist(DAILY_ID, "每日推荐", daily.size, daily.first().coverUrl, false)
        cards += NeteasePlaylist(FM_ID, "私人 FM", 0, daily.getOrNull(1)?.coverUrl ?: daily.firstOrNull()?.coverUrl.orEmpty(), false)
        return cards
    }

    @Volatile private var liked: Set<Long>? = null
    @Volatile private var likedAt = 0L

    /** 这首歌是不是红心（按缓存的列表，过期了在后台重新拉） */
    fun isLiked(id: Long): Boolean = liked?.contains(id) ?: false

    /** 拉一次红心列表（10 分钟内不重复拉） */
    fun loadLiked() {
        if (System.currentTimeMillis() - likedAt < 10 * 60_000L && liked != null) return
        likedAt = System.currentTimeMillis()
        val uid = resolveUser()
        if (uid == 0L) return
        val o = account("https://music.163.com/weapi/song/like/get", JsonObject().apply { addProperty("uid", uid.toString()) }) ?: return
        liked = o.getAsJsonArray("ids")?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull() }?.toHashSet() ?: emptySet()
    }

    /** 加 / 取消红心；成功返回 true */
    fun setLiked(id: Long, like: Boolean): Boolean {
        val o = account("https://music.163.com/weapi/radio/like?alg=itembased&trackId=$id&time=3",
            JsonObject().apply { addProperty("trackId", id.toString()); addProperty("like", like) }) ?: return false
        if (o.get("code")?.asInt != 200) return false
        val now = HashSet(liked ?: emptySet())
        if (like) now.add(id) else now.remove(id)
        liked = now
        return true
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

    // ---------------------------------------------------------------- 搜索

    /** 上一次搜索失败的原因（界面上显示）；成功或只是没结果时为空 */
    @Volatile var lastSearchError: String = ""
        private set

    /** 带登录信息的 cookie 头（weapi 请求用） */
    private fun cookieHeader(): String {
        val c = cookie()
        return if (c.isEmpty()) "os=pc; appver=2.10.13" else "$c; os=pc; appver=2.10.13"
    }

    private fun songsOf(o: JsonObject?): List<NeteaseTrack>? {
        val result = o?.get("result") as? JsonObject ?: return null
        val arr = result.getAsJsonArray("songs") ?: return emptyList()
        return arr.mapNotNull { el -> if (el.isJsonObject) NeteaseTrack.parse(el.asJsonObject) else null }
    }

    private fun codeOf(o: JsonObject?): String =
        o?.get("code")?.takeIf { !it.isJsonNull }?.asString ?: "no response"

    /**
     * 搜歌。网易云经常限制某个接口，所以依次试：
     *   1. cloudsearch（直接带封面、时长）
     *   2. weapi 加密版 cloudsearch（和网页版一样的请求方式，最不容易被拦）
     *   3. 老的网页搜索 + 详情接口补全
     */
    fun search(keyword: String, limit: Int = 50): List<NeteaseTrack> {
        if (keyword.isBlank()) return emptyList()
        val q = keyword.trim()
        val errors = mutableListOf<String>()

        // 1. cloudsearch
        try {
            val o = getJson("https://music.163.com/api/cloudsearch/pc?s=" + enc(q) + "&type=1&offset=0&limit=$limit")
            val songs = songsOf(o)
            if (!songs.isNullOrEmpty()) return done(songs)
            if (songs == null) errors += "cloudsearch " + codeOf(o)
        } catch (e: Exception) {
            errors += "cloudsearch " + e.javaClass.simpleName
        }

        // 2. weapi cloudsearch
        try {
            val body = JsonObject().apply {
                addProperty("s", q)
                addProperty("type", "1")
                addProperty("limit", limit.toString())
                addProperty("offset", "0")
                addProperty("total", "true")
                addProperty("csrf_token", "")
            }.toString()
            val res = NeteaseLogin.weapi("https://music.163.com/weapi/cloudsearch/get/web", body, cookieHeader())
            val root = res?.first?.let { JsonParser().parse(it) }
            val o = if (root != null && root.isJsonObject) root.asJsonObject else null
            val songs = songsOf(o)
            if (!songs.isNullOrEmpty()) return done(songs)
            if (songs == null) errors += "weapi " + codeOf(o)
        } catch (e: Exception) {
            errors += "weapi " + e.javaClass.simpleName
        }

        // 3. 老接口：先搜 ID，再用详情补全封面、时长
        try {
            val o = getJson("https://music.163.com/api/search/get/web?s=" + enc(q) + "&type=1&offset=0&limit=$limit")
            val basic = songsOf(o)
            if (basic == null) {
                errors += "web " + codeOf(o)
            } else if (basic.isNotEmpty()) {
                val detailed = HashMap<Long, NeteaseTrack>()
                try {
                    val res = getJson("https://music.163.com/api/song/detail?ids=" + enc(basic.joinToString(",", "[", "]") { it.id.toString() }))
                    res?.getAsJsonArray("songs")?.forEach { el ->
                        if (el.isJsonObject) NeteaseTrack.parse(el.asJsonObject)?.let { detailed[it.id] = it }
                    }
                } catch (_: Exception) {
                }
                return done(basic.map { t -> detailed[t.id]?.takeIf { it.coverUrl.isNotEmpty() } ?: t })
            }
        } catch (e: Exception) {
            errors += "web " + e.javaClass.simpleName
        }

        // 三个接口都没拿到结果：都正常返回只是没歌 → 没有结果；否则显示失败原因
        lastSearchError = errors.joinToString(", ")
        if (errors.isNotEmpty()) DangoLogger.warn("NetEase search failed: {}", lastSearchError)
        return emptyList()
    }

    private fun done(songs: List<NeteaseTrack>): List<NeteaseTrack> {
        lastSearchError = ""
        return songs
    }

    // ---------------------------------------------------------------- 播放地址

    /**
     * @param gainDb  音源给的响度增益（分贝，0 = 没给），用来把不同歌调到差不多响
     * @param peak    音源给的峰值（线性，0 = 没给），增益不能把它推过 1，不然会破音
     * @param replaced 原来那首放不了，换成了平台推荐的另一个版本
     */
    class SongUrl(val url: String, val trialOnly: Boolean, val gainDb: Float = 0f, val peak: Float = 0f, val replaced: Boolean = false)

    /**
     * 播放地址。登录后能拿到账号有权限的完整音频；拿不到时退回网易云外链（只对免费歌曲有效）。
     * 优先新版 exhigh；只接受播放器支持的 MP3 / Vorbis。
     */
    fun songUrl(id: Long): SongUrl? = songUrl(NeteaseTrack(id, "", emptyList(), 0, ""))

    fun songUrl(track: NeteaseTrack): SongUrl? {
        if (track.cloud) {
            // 同一云盘条目可能同时给上传标识和曲库标识；不能按歌名猜测另一个版本。
            var info = track
            if (track.cloudId == 0L || track.cloudId == track.id) {
                try {
                    info = cloudDetails(listOf(track.cloudId.takeIf { it > 0 } ?: track.id))
                        .firstOrNull { it.id == track.id || it.cloudId == track.id || (track.cloudId > 0 && it.cloudId == track.cloudId) } ?: track
                } catch (_: Exception) { DangoLogger.warn("NetEase cloud detail lookup failed") }
            }
            val upload = info.cloudId.takeIf { it > 0 } ?: info.id
            val aliases = listOf(info.id, upload).distinct()
            var preview: SongUrl? = null
            var unsupported: SourceAccessException? = null
            for (id in listOf(upload, info.id).distinct()) {
                val result = try { playerUrl(id, aliases) } catch (e: SourceAccessException) {
                    if (e.message != "NetEase returned an audio format that this player does not support yet.") throw e
                    unsupported = e
                    null
                } ?: continue
                if (!result.trialOnly) return result
                if (preview == null) preview = result
            }
            if (preview != null) return preview
            unsupported?.let { throw it }
        } else playerUrl(track.id)?.let { return it }
        // 私人上传没有公共曲库替代项；换成另一首会让用户误以为自己的上传播放成功。
        if (track.cloud) throw SourceAccessException("No playable cloud audio was returned. Check this upload in the official NetEase client.")
        alternativeVersion(track.id)?.let { alt ->
            playerUrl(alt)?.let { found -> return SongUrl(found.url, found.trialOnly, found.gainDb, found.peak, replaced = true) }
        }
        return SongUrl("https://music.163.com/song/media/outer/url?id=${track.id}.mp3", false)
    }

    private fun playerUrl(id: Long, aliases: List<Long> = emptyList()): SongUrl? = NeteasePlayback({ stage, body ->
        if (stage == "v1") NeteasePlayerRequest.post(body, cookieHeader())
        else getJson("https://music.163.com/api/song/enhance/player/url?ids=" + enc("[$id]") + "&br=192000")
    }, loggedIn).resolve(id, aliases)

    /** 变灰的歌（privileges.st < 0）里网易云推荐的替代版本 id；没有就是 null */
    private fun alternativeVersion(id: Long): Long? = try {
        val o = getJson("https://music.163.com/api/v3/song/detail?c=" + enc("[{\"id\":$id}]"))
        val st = (o?.getAsJsonArray("privileges")?.firstOrNull() as? JsonObject)?.get("st")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val rcmd = (o?.getAsJsonArray("songs")?.firstOrNull() as? JsonObject)?.get("noCopyrightRcmd")
        val alt = if (rcmd != null && rcmd.isJsonObject) rcmd.asJsonObject.get("songId")?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull() else null
        alt?.takeIf { st < 0 && it > 0 && it != id }
    } catch (e: Exception) {
        DangoLogger.warn("NetEase alternative version lookup failed: {}", e.toString())
        null
    }

    // ---------------------------------------------------------------- HTTP

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun getJson(url: String): JsonObject? {
        val bytes = request(url, accept = "application/json") ?: return null
        val root = JsonParser().parse(String(bytes, StandardCharsets.UTF_8))
        return if (root.isJsonObject) root.asJsonObject else null
    }

    /** 下载（跟随重定向，包括 http↔https）；只对 music.163.com 带 cookie */
    fun request(url: String, accept: String = "*/*", maxBytes: Int = 64 * 1024 * 1024): ByteArray? = try {
        requestOnce(url, accept, maxBytes)
    } catch (e: java.net.SocketException) {
        // 网易云偶尔直接把连接断掉（Connection reset）：等一下再试一次，大多就好了
        if (!(e.message ?: "").contains("reset", ignoreCase = true)) throw e
        DangoLogger.warn("NetEase connection reset, retrying once")
        Thread.sleep(400)
        requestOnce(url, accept, maxBytes)
    }

    private fun requestOnce(url: String, accept: String, maxBytes: Int): ByteArray? {
        var current = url
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", accept)
                if (URL(current).host.endsWith("music.163.com")) {
                    conn.setRequestProperty("Referer", "https://music.163.com/")
                    val c = cookie()
                    conn.setRequestProperty("Cookie", if (c.isEmpty()) "os=pc; appver=2.10.13" else "$c; os=pc; appver=2.10.13")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) return null
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) return null
                    }
                    return out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }
}
