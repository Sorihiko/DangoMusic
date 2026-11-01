package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.music.MusicSources
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.party.ListenParty
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 歌曲列表页（歌单详情、搜索结果、播放队列）：
 *   标题区：返回、歌单封面、播放全部、筛选框、定位、列表 / 封面墙切换
 *   内容：列表（序号 / 小封面 / 歌名 / 歌手 / 时长 + 右侧滚动条），或封面墙（Coverflow）
 */
internal class TracksPage(private val screen: MusicScreen) {
    private val ui = screen.ui

    /** 本帧画了歌曲列表的区域（滚轮用），没画就是空的 */
    var area = LayoutBounds()

    // 歌单内筛选
    var filterText = ""
    var filterFocused = false
    var filterRect = LayoutBounds()
        private set
    private var filterCacheSrc: List<NeteaseTrack>? = null
    private var filterCacheKey = ""
    private var filterCache: List<Pair<Int, NeteaseTrack>> = emptyList()

    // 列表滚动：目标（行）和平滑后的当前位置；一屏放得下几行
    private var scroll = 0
    private var listPos = 0f
    var listRows = 10
        private set

    // 右侧滚动条
    private var sbTrack = LayoutBounds()
    private var sbTotal = 0
    var sbDragging = false
        private set
    private var sbGrab = 0f

    // "定位"后那一行闪一下
    private var flashId = ""
    private var flashAt = 0L

    // 封面墙：目标下标和平滑后的当前位置
    private var cfTarget = 0
    private var cfPos = 0f

    private val tracksPage: Boolean
        get() = ScreenState.page.let { it == Page.PLAYLIST || it == Page.SEARCH || it == Page.QUEUE }

    // ---------------------------------------------------------------- 每帧 / 切页

    fun tick() {
        cfPos = ui.approach(cfPos, cfTarget.toFloat(), 90f)
        if (abs(cfTarget - cfPos) < 0.001f) cfPos = cfTarget.toFloat()
    }

    fun beginFrame() {
        if (!sbDragging) sbTotal = 0   // 没画歌单列表的页面上不响应滚动条
    }

    /** 换了页面：列表直接从当前滚动位置开始画，不从别处滑过来 */
    fun onPageChanged() {
        listPos = scroll.toFloat()
    }

    fun resetScroll() {
        scroll = 0
    }

    fun resetCoverflow() {
        cfTarget = 0
        cfPos = 0f
    }

    fun scroll(wheel: Int, rows: Int) {
        if (ScreenState.coverView) cfTarget += if (wheel > 0) -1 else 1 else scroll += rows
    }

    // ---------------------------------------------------------------- 数据

    fun currentTracks(): Pair<String, List<NeteaseTrack>?> = when (ScreenState.page) {
        Page.SEARCH -> "\"${ScreenState.lastQuery}\"" to Library.searchResults
        Page.QUEUE -> I18n.text("Now Playing") to MusicPlayer.queue
        Page.PLAYLIST -> {
            val p = ScreenState.opened
            // 私人 FM 正在放时显示真正的队列（放着放着会接上新歌）
            (p?.name ?: "") to (if (p == null) null else if (p.id == NeteaseApi.FM_ID && MusicPlayer.radio != null) MusicPlayer.queue else Library.tracks(p.id))
        }
        else -> "" to null
    }

    private fun norm(s: String) = s.lowercase().replace(" ", "")

    /** 当前页显示的歌（筛选后），带原来的序号；结果缓存起来，两千多首也不卡 */
    private fun shown(all: List<NeteaseTrack>): List<Pair<Int, NeteaseTrack>> {
        val key = norm(filterText)
        if (all === filterCacheSrc && key == filterCacheKey) return filterCache
        filterCacheSrc = all
        filterCacheKey = key
        filterCache = if (key.isEmpty()) {
            all.mapIndexed { i, t -> i to t }
        } else {
            all.mapIndexedNotNull { i, t ->
                if (norm(t.name).contains(key) || t.artists.any { norm(it).contains(key) }) i to t else null
            }
        }
        return filterCache
    }

    fun clearFilter() {
        filterText = ""
        filterFocused = false
    }

    /** 播放：队列始终是完整歌单（筛选只影响显示），从点的那首开始 */
    private fun playFrom(all: List<NeteaseTrack>, index: Int) {
        if (ScreenState.page == Page.QUEUE) MusicPlayer.playIndex(index) else MusicPlayer.playQueue(all, index, radio())
    }

    /** 私人 FM 的页面：放完接着要新的歌 */
    private fun radio(): (() -> List<NeteaseTrack>)? =
        if (ScreenState.page == Page.PLAYLIST && ScreenState.opened?.id == NeteaseApi.FM_ID && MusicSources.selected == MusicSource.NETEASE)
            NeteaseApi::personalFm else null

    /** 跳到正在播放的那首：列表滚到它居中，封面墙转到它，并闪一下 */
    private fun locateCurrent() {
        val all = currentTracks().second ?: return
        val cur = MusicPlayer.current ?: return
        var list = shown(all)
        var idx = list.indexOfFirst { it.second.key == cur.key }
        if (idx < 0 && filterText.isNotEmpty()) {
            // 被筛选掉了：清掉筛选再找
            clearFilter()
            list = shown(all)
            idx = list.indexOfFirst { it.second.key == cur.key }
        }
        if (idx < 0) return
        scroll = (idx - listRows / 2).coerceAtLeast(0)
        cfTarget = idx
        cfPos = idx.toFloat()
        flashId = cur.key
        flashAt = System.currentTimeMillis()
    }

    private fun currentInList(): Boolean {
        val cur = MusicPlayer.current ?: return false
        return currentTracks().second?.any { it.key == cur.key } == true
    }

    /** 封面墙转到正在播放的那首（没有就第一首） */
    fun focusCurrentInCoverflow() {
        val tracks = currentTracks().second?.let { shown(it) } ?: return
        val cur = MusicPlayer.current
        val idx = if (cur != null) tracks.indexOfFirst { it.second.key == cur.key } else -1
        cfTarget = if (idx >= 0) idx else 0
        cfPos = cfTarget.toFloat()
    }

    // ---------------------------------------------------------------- 绘制

    fun draw(page: LayoutBounds) {
        val (title, tracks) = currentTracks()
        val y = page.y
        var headerH = 62f
        ui.headerAnim { headerH = drawHeader(page, title, tracks) }
        area = LayoutBounds(page.x, y + headerH, page.width, page.bottom - (y + headerH))
        if (tracks == null) return
        val list = shown(tracks)
        if (list.isEmpty() && filterText.isNotEmpty()) {
            ui.text(I18n.format("No songs match \"%s\"", filterText), area.x + 12f, area.y + 20f, 12f, Theme.TEXT_TERTIARY)
            return
        }
        drawViews(area, tracks, list)
    }

    // 列表 / 封面墙切换：红色选中块用弹簧滑过去（滑动时被拉长一点），内容左右滑入滑出（和设置页的标签切换一样）
    private val viewPill = Spring(300f, 23f, if (ScreenState.coverView) 1f else 0f)
    private val viewFade = Spring(210f, 24f, 1f)
    private var lastCover = ScreenState.coverView
    private var viewFrom: Boolean? = null

    private fun drawViews(area: LayoutBounds, tracks: List<NeteaseTrack>, list: List<Pair<Int, NeteaseTrack>>) {
        val cover = ScreenState.coverView
        if (cover != lastCover) {
            viewFrom = lastCover
            lastCover = cover
            viewFade.x = 0f
            viewFade.v = 0f
        }
        val t = viewFade.tick(1f)
        val from = viewFrom
        if (from == null || t >= 0.999f) {
            viewFrom = null
            if (cover) ui.itemAnim(0) { drawCoverflow(area, tracks, list) } else drawTrackList(area, tracks, list)
            return
        }
        // 封面墙在右边：切到封面墙时内容往左走，切回列表时往右走
        val dir = if (cover) 1f else -1f
        // 两种视图各自会裁剪到 area（裁剪不能嵌套），这里不再套一层
        val out = (1f - t * 1.6f).coerceIn(0f, 1f)
        if (out > 0.01f) {
            // 旧内容只是画出来，点击区域不保留
            val keep = ui.hits.size
            ui.shifted(-dir * 36f * t.coerceIn(0f, 1f), 0f, out) { drawView(from, area, tracks, list) }
            while (ui.hits.size > keep) ui.hits.removeAt(ui.hits.size - 1)
        }
        ui.shifted(dir * 36f * (1f - t), 0f, t.coerceIn(0f, 1f)) { drawView(cover, area, tracks, list) }
    }

    private fun drawView(cover: Boolean, area: LayoutBounds, tracks: List<NeteaseTrack>, list: List<Pair<Int, NeteaseTrack>>) {
        if (cover) drawCoverflow(area, tracks, list) else drawTrackList(area, tracks, list)
    }

    /** 当前打开的歌单已经显示了一部分，后面还在加载 */
    private fun partiallyLoaded(): Boolean =
        ScreenState.page == Page.PLAYLIST && ScreenState.opened?.id?.let { Library.loadingTracks(it) } == true

    /** 当前打开的歌单的加载进度（[已拿到, 总数]） */
    private fun loadingProgress(): IntArray? = ScreenState.opened?.id?.let { Library.trackProgress(it) }

    // 加载进度条的平滑动画（换了歌单就重新开始）
    private var loadKey = -1L
    private var loadBar = Spring(160f, 24f)

    /**
     * 「正在播放」页的删除 / 清空按钮对谁可见：
     * 房主、不在房间的人、被房主允许控制的成员。
     */
    private fun canManageQueue(): Boolean =
        ScreenState.page == Page.QUEUE && when (ListenParty.role) {
            ListenParty.Role.MEMBER -> ListenParty.canControl
            else -> true
        }

    /** 标题区，返回它的高度 */
    private fun drawHeader(page: LayoutBounds, title: String, tracks: List<NeteaseTrack>?): Float {
        val ctx = ui.ctx
        val state = ScreenState
        var x = page.x
        val y = page.y
        var headerH = 62f

        // 返回
        if (state.backPage != null) {
            val bb = LayoutBounds(x, y + 2f, 28f, 28f)
            ctx.drawShape(bb, ShapeStyle(if (ui.hover(bb)) Theme.CARD_HOVER else Theme.CARD, 14f))
            ui.icon("◀", bb.x + 13.5f, bb.y + 14f, 10f, Theme.TEXT_PRIMARY)
            ui.hits += bb to { screen.goBack() }
            x += 40f
        }

        // 歌单封面
        val p = state.opened
        if (state.page == Page.PLAYLIST && p != null) {
            val cv = LayoutBounds(x, y, 96f, 96f)
            ui.drawCover(cv, CoverCache.playlistRounded(p, 0.1f), 12f)
            x = cv.right + 18f
            headerH = 112f
        }

        val header = when (state.page) {
            Page.SEARCH -> I18n.format("Search  \"%s\"", state.lastQuery)
            Page.QUEUE -> I18n.text("Now Playing")
            else -> title
        }
        val segW = 132f
        ui.text(ui.trim(header, page.right - segW - 24f - x, 22f, Theme.FONT_DISPLAY), x, y + 4f, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        val sub = when {
            state.page == Page.SEARCH && Library.searching -> I18n.text("Searching...")
            state.page == Page.SEARCH && tracks.isNullOrEmpty() ->
                if (Library.searchError.isNotEmpty()) I18n.text("Search failed: ") + I18n.message(Library.searchError) else I18n.text("No results")
            state.page == Page.QUEUE && tracks.isNullOrEmpty() -> I18n.text("Nothing in the queue yet")
            tracks == null || partiallyLoaded() -> loadingProgress()?.takeIf { it[1] > 0 }?.let { I18n.format("Loaded %d of %d songs", it[0], it[1]) } ?: I18n.text("Loading...")
            filterText.isNotEmpty() -> I18n.format("%d of %d songs", shown(tracks).size, tracks.size)
            else -> I18n.format("%d songs", tracks.size)
        }
        ui.text(sub, x, y + 34f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        // 歌单还在加载：知道总数就按比例走（平滑地追上去），不知道就来回滑，表示没卡住
        // 已经先显示了一部分时，进度条挪到"播放全部"右边，不和按钮重叠
        val partial = tracks != null && partiallyLoaded()
        if ((tracks == null || partial) && state.page != Page.SEARCH && state.page != Page.QUEUE) {
            val pr = loadingProgress()
            val key = state.opened?.id ?: 0L
            if (key != loadKey) {
                loadKey = key
                loadBar = Spring(160f, 24f)
            }
            val target = pr?.takeIf { it[1] > 0 }?.let { (it[0].toFloat() / it[1]).coerceIn(0f, 1f) }
            val shownFraction = target?.let { loadBar.tick(it) }
            val barX = if (partial) x + ui.width(I18n.text("Play all"), 12f, Theme.FONT_SEMIBOLD) + 44f + 14f else x
            val barY = if (partial) y + (if (headerH > 100f) 64f else 52f) + 7f else y + 54f
            ui.progressBar(LayoutBounds(barX, barY, 180f, 4f), shownFraction)
        }

        // 播放全部
        if (!tracks.isNullOrEmpty() && state.page != Page.QUEUE) {
            val label = I18n.text("Play all")
            val pb = LayoutBounds(x, y + (if (headerH > 100f) 64f else 52f) - 6f, ui.width(label, 12f, Theme.FONT_SEMIBOLD) + 44f, 30f)
            ctx.drawShape(pb, ShapeStyle(if (ui.hover(pb)) RED.copy(a = 0.85f) else RED, 15f))
            ui.icon("▶", pb.x + 17f, pb.y + 15f, 11f, Theme.TEXT_PRIMARY)
            ui.text(label, pb.x + 30f, pb.y + 9f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            ui.hits += pb to { MusicPlayer.playQueue(tracks, if (MusicPlayer.shuffle && radio() == null) (tracks.indices).random() else 0, radio()) }
            if (headerH < 100f) headerH = 92f
        }

        // 右上角：列表 / 封面墙
        val seg = LayoutBounds(page.right - segW, y + 4f, segW, 26f)
        ui.card(seg, Theme.CARD, 13f)
        val half = LayoutBounds(seg.x, seg.y, segW / 2f, seg.height)
        val other = LayoutBounds(seg.x + segW / 2f, seg.y, segW / 2f, seg.height)
        val coverView = state.coverView
        if (ui.hover(if (coverView) half else other)) ui.card(if (coverView) half else other, Theme.CARD_HOVER, 13f)
        val pill = viewPill.tick(if (coverView) 1f else 0f)
        val stretch = min(14f, abs(viewPill.v) * 5f)
        val px = seg.x + pill.coerceIn(-0.06f, 1.06f) * half.width - (if (viewPill.v > 0f) stretch else 0f)
        ctx.drawShape(LayoutBounds(px, seg.y, half.width + stretch, seg.height), ShapeStyle(RED, 13f))
        val on = pill.coerceIn(0f, 1f)
        ui.text(I18n.text("List"), half.x + (half.width - ui.width(I18n.text("List"), 12f, Theme.FONT_MEDIUM)) / 2f, half.y + 8f, 12f,
            mixColor(Theme.TEXT_SECONDARY, Theme.TEXT_PRIMARY, 1f - on), Theme.FONT_MEDIUM)
        ui.text(I18n.text("Covers"), other.x + (other.width - ui.width(I18n.text("Covers"), 12f, Theme.FONT_MEDIUM)) / 2f, other.y + 8f, 12f,
            mixColor(Theme.TEXT_SECONDARY, Theme.TEXT_PRIMARY, on), Theme.FONT_MEDIUM)
        ui.hits += half to { if (ScreenState.coverView) screen.toggleCoverView() }
        ui.hits += other to { if (!ScreenState.coverView) screen.toggleCoverView() }

        // 定位到正在播放（列表里有这首歌时才显示）
        var rightX = seg.x - 10f
        if (currentInList()) {
            val label = I18n.text("Locate")
            val lw = ui.width(label, 11f, Theme.FONT_MEDIUM) + 34f
            val lb = LayoutBounds(rightX - lw, y + 4f, lw, 26f)
            ui.card(lb, if (ui.hover(lb)) Theme.CARD_HOVER else Theme.CARD, 13f)
            ui.icon("◎", lb.x + 14f, lb.y + 13f, 11f, RED)
            ui.text(label, lb.x + 25f, lb.y + 8f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
            ui.hits += lb to { locateCurrent() }
            rightX = lb.x - 10f
        }

        // 清空当前播放列表：和删除键一样动态，房主或房主允许控制的成员才看得到
        if (canManageQueue()) {
            val label = I18n.text("Clear queue")
            val cw = ui.width(label, 11f, Theme.FONT_MEDIUM) + 34f
            val cb = LayoutBounds(rightX - cw, y + 4f, cw, 26f)
            ui.card(cb, if (ui.hover(cb)) Theme.CARD_HOVER else Theme.CARD, 13f)
            ui.icon("✕", cb.x + 14f, cb.y + 13f, 11f, RED)
            ui.text(label, cb.x + 25f, cb.y + 8f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
            ui.hits += cb to { MusicPlayer.clearQueue() }
            rightX = cb.x - 10f
        }

        // 歌单内筛选框（按歌名 / 歌手，输入即筛选）
        if (tracks != null && state.page != Page.SEARCH) drawFilter(rightX, y) else filterRect = LayoutBounds()
        return headerH
    }

    private fun drawFilter(rightX: Float, y: Float) {
        val ctx = ui.ctx
        val fw = 190f
        filterRect = LayoutBounds(rightX - fw, y + 4f, fw, 26f)
        ui.card(filterRect, if (filterFocused) Theme.CARD_HOVER else Theme.CARD, 13f)
        if (filterFocused) ctx.drawShape(filterRect, ShapeStyle(Color(0f, 0f, 0f, 0f), 13f, 1f, RED))
        ctx.drawShape(LayoutBounds(filterRect.x + 10f, filterRect.y + 8f, 8f, 8f), ShapeStyle(Color(0f, 0f, 0f, 0f), 4f, 1.3f, Theme.TEXT_SECONDARY))
        ctx.drawShape(LayoutBounds(filterRect.x + 16.5f, filterRect.y + 15f, 4f, 1.5f), ShapeStyle(Theme.TEXT_SECONDARY, 0.75f))
        val caret = if (filterFocused && caretVisible()) "|" else ""
        val placeholder = filterText.isEmpty() && !filterFocused
        var shownText = if (placeholder) I18n.text("Filter this list...") else filterText + caret
        val maxW = filterRect.width - 30f - (if (filterText.isNotEmpty()) 18f else 0f)
        while (shownText.length > 1 && ui.width(shownText, 11f) > maxW) shownText = shownText.substring(1)
        ui.text(shownText, filterRect.x + 26f, filterRect.y + 8f, 11f, if (placeholder) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY)
        ui.hits += filterRect to {
            filterFocused = true
            screen.sidebar.searchFocused = false
        }
        if (filterText.isNotEmpty()) {
            val xb = LayoutBounds(filterRect.right - 22f, filterRect.y + 4f, 18f, 18f)
            if (ui.hover(xb)) ctx.drawShape(xb, ShapeStyle(Theme.CARD_HOVER, 9f))
            ui.icon("×", xb.x + 9f, xb.y + 9f, 11f, Theme.TEXT_SECONDARY)
            ui.hits.add(0, xb to { clearFilter() })     // 优先于筛选框本身
        }
    }

    // ---------------------------------------------------------------- 列表 + 滚动条

    /** 滚动条滑块的位置和高度 */
    private fun thumbOf(track: LayoutBounds, total: Int, rows: Int): LayoutBounds {
        val h = max(28f, track.height * rows / total.coerceAtLeast(1))
        val maxScroll = max(1, total - rows)
        val pos = if (sbDragging) scroll.toFloat() else listPos
        val y = track.y + (track.height - h) * (pos / maxScroll).coerceIn(0f, 1f)
        return LayoutBounds(track.x, y, track.width, h)
    }

    /**
     * 歌单右边的滚动条：拖滑块快速滚动，点空白处直接跳过去；
     * 拖动时旁边显示现在在第几首。
     */
    private fun drawScrollbar(track: LayoutBounds, total: Int, rows: Int) {
        sbTrack = track
        sbTotal = total
        if (total <= rows) {
            sbTrack = LayoutBounds()
            return
        }
        val hot = sbDragging || track.expand(8f, 0f).contains(ui.mx, ui.my)
        ui.ctx.drawShape(track, ShapeStyle(Color(1f, 1f, 1f, if (hot) 0.10f else 0.05f), track.width / 2f))
        val th = thumbOf(track, total, rows)
        val thumb = if (hot) LayoutBounds(th.x - 1.5f, th.y, th.width + 3f, th.height) else th
        ui.ctx.drawShape(thumb, ShapeStyle(if (sbDragging) RED else Color(1f, 1f, 1f, if (hot) 0.55f else 0.30f), thumb.width / 2f))
        if (sbDragging) {
            val label = "${scroll + 1} / $total"
            val lw = ui.width(label, 11f, Theme.FONT_SEMIBOLD) + 18f
            val lb = LayoutBounds(track.x - lw - 10f, th.y + th.height / 2f - 12f, lw, 24f)
            ui.ctx.drawGlass(lb, Theme.GLASS_SELECTION)
            ui.text(label, lb.x + 9f, lb.y + 7f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        }
    }

    private fun scrollbarTo(y: Int) {
        val track = sbTrack
        val rows = listRows
        if (sbTotal <= rows || track.height <= 0f) return
        val th = thumbOf(track, sbTotal, rows)
        val frac = ((y - sbGrab) - track.y) / max(1f, track.height - th.height)
        scroll = (frac.coerceIn(0f, 1f) * (sbTotal - rows)).toInt()
    }

    /** 按在滚动条上：按在滑块上就拖，按在空白处就把滑块中心跳过去再拖。返回 true 表示按到了 */
    fun scrollbarDown(mx: Int, my: Int): Boolean {
        if (ScreenState.coverView || sbTotal <= listRows || sbTrack.height <= 0f || !sbTrack.expand(8f, 0f).contains(mx, my)) return false
        val th = thumbOf(sbTrack, sbTotal, listRows)
        sbGrab = if (my >= th.y && my <= th.bottom) my - th.y else th.height / 2f
        sbDragging = true
        scrollbarTo(my)
        return true
    }

    fun scrollbarDrag(y: Int) = scrollbarTo(y)

    fun scrollbarUp() {
        sbDragging = false
    }

    /** 列表：序号 / 小封面 / 歌名 / 歌手 / 时长 */
    private fun drawTrackList(list: LayoutBounds, all: List<NeteaseTrack>, tracks: List<Pair<Int, NeteaseTrack>>) {
        val ctx = ui.ctx
        // 表头
        ui.text("#", list.x + 12f, list.y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        ui.text(I18n.text("TITLE"), list.x + 84f, list.y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        val sbW = 16f   // 右边留给滚动条
        ui.text(I18n.text("TIME"), list.right - sbW - 12f - ui.width(I18n.text("TIME"), 10f, Theme.FONT_SEMIBOLD), list.y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        ctx.drawShape(LayoutBounds(list.x, list.y + 16f, list.width - sbW, 1f), ShapeStyle(Theme.SEPARATOR))
        val body = LayoutBounds(list.x, list.y + 22f, list.width - sbW, list.height - 22f)

        val rowH = 44f
        val rows = max(1, (body.height / rowH).toInt())
        listRows = rows
        scroll = scroll.coerceIn(0, max(0, tracks.size - rows))
        listPos = if (sbDragging) scroll.toFloat() else ui.easeScroll(listPos, scroll, rows * 3)
        drawScrollbar(LayoutBounds(list.right - 9f, body.y + 2f, 5f, rows * rowH - 8f), tracks.size, rows)
        val flash = (1f - (System.currentTimeMillis() - flashAt) / 1400f).coerceIn(0f, 1f)
        val playing = MusicPlayer.current
        // 「正在播放」页每行右边有「删除」：只从队列里去掉，不动歌单。
        // 可见性和顶部「清空」一致，见 [canManageQueue]。
        val canRemove = canManageQueue()
        val removeW = if (canRemove) 50f else 0f
        val first = kotlin.math.floor(listPos).toInt()
        val frac = listPos - first
        val clipArea = LayoutBounds(body.x - 4f, body.y - 2f, body.width + 8f, rows * rowH)
        ui.beginClip(clipArea)
        for (r in 0..rows) {
            val vi = first + r
            if (vi < 0) continue
            if (vi >= tracks.size) break
            val (i, t) = tracks[vi]
            ui.itemAnim(r) {
                val row = LayoutBounds(body.x, body.y + (r - frac) * rowH, body.width, rowH - 4f)
                val isCur = playing != null && playing.key == t.key
                when {
                    isCur -> ctx.drawGlass(row, Theme.GLASS_SELECTION)
                    ui.hover(row) -> ui.card(row, Theme.CARD_HOVER, 10f)
                }
                if (flash > 0f && t.key == flashId) {
                    ctx.drawShape(row, ShapeStyle(Color(0f, 0f, 0f, 0f), 10f, 1.5f, RED.copy(a = flash)))
                }
                if (isCur && MusicPlayer.state == MusicPlayer.State.PLAYING) {
                    ui.icon("♫", row.x + 18f, row.y + 20f, 12f, RED)
                } else {
                    ui.text("${i + 1}", row.x + 12f, row.y + 15f, 11f, Theme.TEXT_TERTIARY)
                }
                val thumb = LayoutBounds(row.x + 46f, row.y + 4f, row.height - 8f, row.height - 8f)
                ui.drawCover(thumb, CoverCache.trackRounded(t, 0.15f), 6f)
                val tx = thumb.right + 12f
                val durW = 44f
                val avail = row.right - durW - 12f - tx - removeW
                ui.text(ui.trim(t.name, avail, 13f), tx, row.y + 7f, 13f, if (isCur) RED else Theme.TEXT_PRIMARY)
                ui.text(ui.trim(t.artists.joinToString(" / "), avail, 11f), tx, row.y + 23f, 11f, Theme.TEXT_SECONDARY)
                val d = fmtTime(t.durationMs)
                ui.text(d, row.right - 12f - ui.width(d, 11f) - removeW, row.y + 15f, 11f, Theme.TEXT_TERTIARY)
                if (canRemove) drawRemoveButton(row, i, clipArea)
                ui.hits += row.intersect(clipArea) to { playFrom(all, i) }
            }
        }
        ui.endClip()
    }

    /**
     * 「删除」两个字：只从播放队列里去掉这一首。点击区域先登记，
     * 否则整行的「播放这首」会先命中（hits 是从前往后匹配的）。
     */
    private fun drawRemoveButton(row: LayoutBounds, position: Int, clip: LayoutBounds) {
        val label = I18n.text("Remove")
        val textWidth = ui.width(label, 10f, Theme.FONT_SEMIBOLD)
        val x = row.right - 12f - textWidth
        val hit = LayoutBounds(x - 5f, row.y + 8f, textWidth + 10f, row.height - 16f)
        ui.text(label, x, row.y + 15f, 10f, if (ui.hover(hit)) RED else Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        ui.hits += hit.intersect(clip) to { MusicPlayer.removeFromQueue(position) }
    }

    // ---------------------------------------------------------------- 封面墙（Coverflow）

    /**
     * 中间一张大封面，两边的依次变小、变淡、往后叠。
     * 滚轮 / ←→ / 点两侧切换，点中间那张（或回车）播放。
     */
    private fun drawCoverflow(area: LayoutBounds, all: List<NeteaseTrack>, shownList: List<Pair<Int, NeteaseTrack>>) {
        val tracks = shownList.map { it.second }
        if (tracks.isEmpty()) return
        cfTarget = cfTarget.coerceIn(0, tracks.size - 1)

        val size = min(area.height * 0.66f, 240f)
        val cx = area.x + area.width / 2f
        // 封面 + 下面三行字作为一个整体，在区域里竖直居中（稍微偏上一点点）
        val blockH = size + 66f
        val cy = area.y + max(8f, (area.height - blockH) * 0.45f) + size / 2f
        val first = max(0, cfTarget - 6)
        val last = min(tracks.size - 1, cfTarget + 6)

        class Item(val i: Int, val d: Float, val b: LayoutBounds)
        val items = (first..last).map { i ->
            val d = i - cfPos
            val ad = abs(d)
            val sign = if (d < 0) -1f else 1f
            val offset = if (ad < 1f) ad * size * 0.62f else size * 0.62f + (ad - 1f) * size * 0.30f
            val scale = 1f - min(ad, 3f) * 0.15f
            val s = size * scale
            Item(i, d, LayoutBounds(cx + sign * offset - s / 2f, cy - s / 2f, s, s))
        }.filter { it.b.right > area.x && it.b.x < area.right }

        // 左右两边：越靠近区域边缘越透明，超出区域的部分裁掉
        val fadeW = area.width * 0.22f
        fun edgeFade(b: LayoutBounds): Float {
            val mid = b.x + b.width / 2f
            val e = (min(mid - area.x, area.right - mid) / fadeW).coerceIn(0f, 1f)
            return e * e * (3f - 2f * e)
        }
        val clip = LayoutBounds(area.x, area.y - 12f, area.width, area.height + 12f)
        ui.beginClip(clip)
        for (item in items.sortedByDescending { abs(it.d) }) {
            val ad = abs(item.d)
            val a = (1f - min(ad, 4f) * 0.2f).coerceAtLeast(0.15f) * edgeFade(item.b)
            if (a < 0.01f) continue
            ui.ctx.pushOpacity(a)
            ui.drawCover(item.b, CoverCache.trackRounded(tracks[item.i], 0.08f), 12f)
            if (ad > 0.5f) ui.card(item.b, Color(0f, 0f, 0f, min(0.45f, ad * 0.18f)), 2f)
            ui.ctx.popOpacity()
        }
        ui.endClip()
        for (item in items.sortedBy { abs(it.d) }) {
            if (edgeFade(item.b) < 0.2f) continue     // 几乎看不见的就不响应点击
            val i = item.i
            ui.hits += item.b.intersect(clip) to {
                if (i == cfTarget) playFrom(all, shownList[i].first) else cfTarget = i
            }
        }

        val t = tracks[cfTarget]
        val isCur = MusicPlayer.current?.key == t.key
        val ty = cy + size / 2f + 14f
        val name = ui.trim(t.name, area.width - 40f, 16f, Theme.FONT_SEMIBOLD)
        ui.text(name, cx - ui.width(name, 16f, Theme.FONT_SEMIBOLD) / 2f, ty, 16f, if (isCur) RED else Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val artist = ui.trim(t.artists.joinToString(" / "), area.width - 40f, 12f)
        ui.text(artist, cx - ui.width(artist, 12f) / 2f, ty + 21f, 12f, Theme.TEXT_SECONDARY)
        val idx = "${cfTarget + 1} / ${tracks.size}"
        ui.text(idx, cx - ui.width(idx, 11f) / 2f, ty + 38f, 11f, Theme.TEXT_TERTIARY)
    }

    // ---------------------------------------------------------------- 按键

    /** 筛选框有焦点时的按键；返回 true 表示按键被吃掉了 */
    fun filterKeyTyped(typedChar: Char, keyCode: Int): Boolean {
        if (!filterFocused) return false
        when {
            keyCode == Keys.ESCAPE -> clearFilter()
            keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> filterFocused = false
            keyCode == Keys.BACK -> if (filterText.isNotEmpty()) filterText = filterText.dropLast(1)
            DangoClient.platform.ctrlDown && keyCode == Keys.V -> {
                val clip = DangoClient.platform.clipboard.replace(Regex("[\\r\\n]"), " ")
                filterText = (filterText + clip).take(60)
            }
            DangoClient.platform.ctrlDown && keyCode == Keys.A -> filterText = ""
            Keys.isAllowedChar(typedChar) -> filterText = (filterText + typedChar).take(60)
        }
        resetScroll()
        resetCoverflow()
        return true
    }

    /**
     * 歌曲列表页的快捷键（在通用快捷键之前处理）：
     *   Ctrl+F 在歌单里筛选，L 定位到正在播放，Home / End / PgUp / PgDn 翻列表
     */
    fun shortcutBefore(keyCode: Int): Boolean {
        if (!tracksPage) return false
        val listView = !ScreenState.coverView
        when {
            ScreenState.page != Page.SEARCH && DangoClient.platform.ctrlDown && keyCode == Keys.F -> filterFocused = true
            keyCode == Keys.L -> locateCurrent()
            listView && keyCode == Keys.HOME -> scroll = 0
            listView && keyCode == Keys.END -> scroll = Int.MAX_VALUE / 2
            listView && keyCode == Keys.PRIOR -> scroll -= listRows
            listView && keyCode == Keys.NEXT -> scroll += listRows
            else -> return false
        }
        return true
    }

    /** 封面墙：←→ 切换，回车播放（在空格 / 退格之后、←→ 快进快退之前处理） */
    fun coverflowKey(keyCode: Int): Boolean {
        if (!tracksPage || !ScreenState.coverView) return false
        when (keyCode) {
            Keys.RIGHT -> cfTarget++
            Keys.LEFT -> cfTarget--
            Keys.RETURN, Keys.NUMPADENTER -> {
                val all = currentTracks().second
                if (all != null) {
                    val list = shown(all)
                    if (cfTarget in list.indices) playFrom(all, list[cfTarget].first)
                }
            }
            else -> return false
        }
        return true
    }
}
