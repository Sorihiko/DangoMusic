package me.sorihiko.dango.musicui

import me.sorihiko.dango.music.TrackLoadProgress
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.music.NeteasePlaylist
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicSourceApi
import me.sorihiko.dango.music.MusicSources
import me.sorihiko.dango.music.SourceAccessException
import me.sorihiko.dango.music.SourceArtist

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal enum class Page { HOME, CHARTS, PLAYLIST, SEARCH, QUEUE, PARTY, SETTINGS, ARTIST }

/** 点进歌手页、从歌手页点进专辑之前的界面，返回时一层层退回去 */
internal class PageSnapshot(
    val page: Page,
    val opened: NeteasePlaylist?,
    val backPage: Page?,
    val artist: ArtistRef?,
    val lastQuery: String,
)

/** 关掉播放器再打开时还保留的界面状态 */
internal object ScreenState {
    var page = Page.HOME
    /** 设置页：0 = Lyrics，1 = Music & HUD；以及从哪一页点进来的 */
    var settingsTab = 0
    var settingsBack = Page.HOME
    var backPage: Page? = null
    var opened: NeteasePlaylist? = null
    /** 歌单页显示方式：false = 列表，true = 封面墙 */
    var coverView = false
    var lastQuery = ""
    /** 歌手页显示的歌手 */
    var artist: ArtistRef? = null
    /** 进歌手页 / 从歌手页进专辑时记下来的上一层（最多记 20 层） */
    val trail = ArrayDeque<PageSnapshot>()

    // 液态玻璃画布（和菜单同一套），第一次打开播放器时创建
    var context: GuiCanvas? = null
}

/** Requests carry their source and generation so late responses cannot overwrite another source. */
internal object Library {
    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "Dango-MusicUI").apply { isDaemon = true } }
    @Volatile var playlists: List<NeteasePlaylist> = emptyList()
        private set
    @Volatile var playlistsLoading = false
        private set
    // 文本和期限一起发布，避免后台加载结果被旧提示的到期逻辑清掉。
    private class Status(val text: String, val expiresAt: Long = 0L)
    @Volatile private var statusState = Status("")
    var status: String
        get() {
            val value = statusState
            return if (value.expiresAt != 0L && System.nanoTime() - value.expiresAt >= 0L) "" else value.text
        }
        private set(value) { statusState = Status(value) }
    @Volatile var recommend: List<NeteasePlaylist>? = null
        private set
    @Volatile var recommendLoading = false
        private set
    @Volatile var charts: List<NeteasePlaylist>? = null
        private set
    @Volatile var chartsLoading = false
        private set
    @Volatile var searchResults: List<NeteaseTrack>? = null
        private set
    @Volatile var searching = false
        private set
    @Volatile var searchError = ""
        private set
    private val tracksCache = ConcurrentHashMap<Long, List<NeteaseTrack>>()
    private val tracksLoading = ConcurrentHashMap.newKeySet<Long>()
    private val tracksFailed = ConcurrentHashMap.newKeySet<Long>()
    private var lastLoadAt = 0L
    private var source = MusicSources.selected
    private var generation = 0
    private var searchSerial = 0
    val stale: Boolean get() = System.currentTimeMillis() - lastLoadAt > 5 * 60_000L
    fun tracks(playlistId: Long): List<NeteaseTrack>? = tracksCache[playlistId]

    /** 正在加载的歌单：[已拿到, 总数]（总数 0 表示不知道），没在加载时为 null */
    private val trackProgress = ConcurrentHashMap<Long, IntArray>()
    fun trackProgress(playlistId: Long): IntArray? = trackProgress[playlistId]

    @Synchronized fun ensureSource() {
        if (source != MusicSources.selected) refreshAll()
    }
    private class Request(val generation: Int, val api: MusicSourceApi)
    private fun request() = Request(generation, MusicSources.api(source))
    @Synchronized private fun publish(request: Request, apply: () -> Unit) {
        if (request.generation == generation) apply()
    }
    @Synchronized fun reloadPlaylists() {
        if (playlistsLoading) return
        val request = request()
        playlistsLoading = true
        lastLoadAt = System.currentTimeMillis()
        worker.execute {
            var error = ""
            val result = try { request.api.playlists() } catch (e: Exception) { error = publicError(e); emptyList() }
            publish(request) {
                playlists = result
                playlistsLoading = false
                status = if (error.isNotEmpty()) error else if (request.api.libraryWarning.isNotEmpty()) request.api.libraryWarning
                    else if (request.api.source != MusicSource.NETEASE) ""
                    else if (result.isEmpty()) "没有读到歌单（检查网络或登录）" else ""
            }
        }
    }
    @Synchronized fun loadRecommend() {
        if (recommend != null || recommendLoading) return
        val request = request()
        recommendLoading = true
        worker.execute {
            var error = ""
            val result = try { request.api.recommend() } catch (e: Exception) { error = publicError(e); emptyList() }
            publish(request) { recommend = if (error.isEmpty()) result else null; recommendLoading = false; if (error.isNotEmpty()) status = error }
        }
    }
    @Synchronized fun loadCharts() {
        if (charts != null || chartsLoading) return
        val request = request()
        chartsLoading = true
        worker.execute {
            var error = ""
            val result = try { request.api.charts() } catch (e: Exception) { error = publicError(e); emptyList() }
            publish(request) { charts = if (error.isEmpty()) result else null; chartsLoading = false; if (error.isNotEmpty()) status = error }
        }
    }
    @Synchronized fun loadTracks(playlistId: Long) {
        if ((tracksCache.containsKey(playlistId) && !tracksFailed.remove(playlistId)) || !tracksLoading.add(playlistId)) return
        val request = request()
        worker.execute {
            var error = ""
            // 大歌单边加载边显示：每拿到一批就先放进缓存，界面马上能看到、能点歌
            val partial: (List<NeteaseTrack>) -> Unit = { list -> publish(request) { replaceTracks(playlistId, list) } }
            val result = try {
                TrackLoadProgress.track({ loaded, total -> trackProgress[playlistId] = intArrayOf(loaded, total) }, partial) { request.api.playlistTracks(playlistId) }
            } catch (e: Exception) { error = publicError(e); null }
            trackProgress.remove(playlistId)
            publish(request) {
                // 失败时保留已经显示的那部分
                if (result != null) replaceTracks(playlistId, result) else if (!tracksCache.containsKey(playlistId)) tracksCache[playlistId] = emptyList()
                tracksLoading.remove(playlistId)
                if (error.isNotEmpty()) { tracksFailed.add(playlistId); status = error }
            }
        }
    }
    /** 换上更新（更长）的歌曲列表；如果正在放的就是旧列表，队列也一起换成新的 */
    private fun replaceTracks(playlistId: Long, tracks: List<NeteaseTrack>) {
        val old = tracksCache[playlistId]
        tracksCache[playlistId] = tracks
        if (old != null) MusicPlayer.growQueue(old, tracks)
    }

    /** 这个歌单是不是还在加载（可能已经先显示了一部分） */
    fun loadingTracks(playlistId: Long): Boolean = tracksLoading.contains(playlistId)

    @Synchronized fun search(q: String) {
        val request = request()
        val serial = ++searchSerial
        searching = true
        searchResults = null
        searchError = ""
        worker.execute {
            var error = ""
            val result = try {
                request.api.search(q).also {
                    if (request.api.source == MusicSource.NETEASE) error = NeteaseApi.lastSearchError
                }
            } catch (e: Exception) {
                // 记下真正的原因（以前直接吞掉，日志里什么都看不到）
                me.sorihiko.dango.DangoLogger.warn("{} search failed: {}", request.api.source.key, e.toString())
                error = publicError(e)
                emptyList()
            }
            publish(request) {
                if (serial == searchSerial) { searchResults = result; searchError = error; searching = false }
            }
        }
    }
    @Synchronized fun softRefresh() {
        lastLoadAt = System.currentTimeMillis()
        val request = request()
        val ids = tracksCache.keys.toList()
        worker.execute {
            val list = try { request.api.playlists() } catch (_: Exception) { emptyList() }
            publish(request) { if (list.isNotEmpty()) playlists = list }
            for (id in ids) {
                val tracks = try { request.api.playlistTracks(id) } catch (_: Exception) { emptyList() }
                publish(request) { if (tracks.isNotEmpty()) tracksCache[id] = tracks }
            }
        }
    }
    @Synchronized fun refreshAll() {
        generation++
        searchSerial++
        source = MusicSources.selected
        playlists = emptyList(); playlistsLoading = false
        tracksCache.clear(); tracksLoading.clear(); tracksFailed.clear(); trackProgress.clear()
        recommend = null; recommendLoading = false
        charts = null; chartsLoading = false
        searchResults = null; searching = false; searchError = ""; status = ""
        reloadPlaylists()
        loadRecommend()
        if (ScreenState.page == Page.CHARTS) loadCharts()
    }

    fun showStatus(message: String) { status = message }

    fun showTemporaryStatus(message: String, durationMs: Long) {
        statusState = Status(message, System.nanoTime() + durationMs.coerceIn(0L, 60_000L) * 1_000_000L)
    }

    // 网易云红心：列表在后台拉，点了在后台改
    @Volatile private var likedCheckedAt = 0L
    @Volatile var liking = false
        private set

    fun ensureLiked() {
        val now = System.currentTimeMillis()
        if (now - likedCheckedAt < 30_000L || !NeteaseApi.loggedIn) return
        likedCheckedAt = now
        worker.execute { try { NeteaseApi.loadLiked() } catch (_: Exception) { } }
    }

    fun toggleLike(track: NeteaseTrack) {
        if (liking || track.source != MusicSource.NETEASE) return
        liking = true
        val like = !NeteaseApi.isLiked(track.id)
        worker.execute {
            val ok = try { NeteaseApi.setLiked(track.id, like) } catch (_: Exception) { false }
            liking = false
            if (!ok) status = if (like) "Could not add to Liked songs. Please retry." else "Could not remove from Liked songs. Please retry."
        }
    }
    // ---------------------------------------------------------------- 歌手（各音源的歌手页）

    /** 缓存的键：音源 + 歌手 ID */
    private fun artistKey(source: MusicSource, id: String) = "${source.key}:$id"

    private val artists = ConcurrentHashMap<String, SourceArtist>()
    private val artistAlbumLists = ConcurrentHashMap<String, List<NeteasePlaylist>>()
    private val artistLoading = ConcurrentHashMap.newKeySet<String>()
    private val artistFailedIds = ConcurrentHashMap.newKeySet<String>()
    /** 按名字找到的歌手 ID（"" = 没找到） */
    private val artistIds = ConcurrentHashMap<String, String>()
    private val artistResolving = ConcurrentHashMap.newKeySet<String>()

    fun artist(source: MusicSource, id: String): SourceArtist? = artists[artistKey(source, id)]
    fun artistAlbums(source: MusicSource, id: String): List<NeteasePlaylist>? = artistAlbumLists[artistKey(source, id)]
    fun artistFailed(source: MusicSource, id: String): Boolean = artistFailedIds.contains(artistKey(source, id))

    /** 后台拉歌手信息，拿到后再拉专辑（已经有了或者正在拉就不重复） */
    fun loadArtist(source: MusicSource, id: String) {
        val key = artistKey(source, id)
        if (artists.containsKey(key) || artistFailedIds.contains(key) || !artistLoading.add(key)) return
        val api = MusicSources.api(source)
        worker.execute {
            try {
                val info = api.artist(id)
                if (info == null) {
                    artistFailedIds.add(key)
                } else {
                    artists[key] = info
                    artistAlbumLists[key] = try { api.artistAlbums(id) } catch (e: Exception) {
                        me.sorihiko.dango.DangoLogger.warn("{} artist albums failed: {}", source.key, e.toString())
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                me.sorihiko.dango.DangoLogger.warn("{} artist load failed: {}", source.key, e.toString())
                artistFailedIds.add(key)
            } finally {
                artistLoading.remove(key)
            }
        }
    }

    /** 歌手 ID：还在按名字找时返回 null，找不到返回 "" */
    fun artistIdFor(source: MusicSource, name: String): String? {
        val key = artistKey(source, name)
        artistIds[key]?.let { return it }
        if (artistResolving.add(key)) {
            val api = MusicSources.api(source)
            worker.execute {
                val id = try { api.findArtist(name) } catch (_: Exception) { "" }
                artistIds[key] = id
                artistResolving.remove(key)
            }
        }
        return null
    }

    /** 加载失败后点了重试 */
    fun retryArtist(source: MusicSource, name: String, id: String?) {
        if (id.isNullOrEmpty()) artistIds.remove(artistKey(source, name))
        else artistFailedIds.remove(artistKey(source, id))
    }

    private fun publicError(error: Exception) = if (error is SourceAccessException) error.message.orEmpty() else "Source unavailable. Please retry."
}
