package me.sorihiko.dango.music

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.musicui.ArtistPage
import me.sorihiko.dango.musicui.ArtistRef
import me.sorihiko.dango.musicui.GridPage
import me.sorihiko.dango.musicui.Library
import me.sorihiko.dango.musicui.LoginDialog
import me.sorihiko.dango.musicui.Page
import me.sorihiko.dango.musicui.PageSnapshot
import me.sorihiko.dango.musicui.PartyPage
import me.sorihiko.dango.musicui.PlayerBar
import me.sorihiko.dango.musicui.RED
import me.sorihiko.dango.musicui.ScreenState
import me.sorihiko.dango.musicui.SettingsPage
import me.sorihiko.dango.musicui.Sidebar
import me.sorihiko.dango.musicui.TracksPage
import me.sorihiko.dango.musicui.Ui
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import kotlin.math.min

/**
 * 内置网易云播放器界面（液态玻璃风格，排版参考 MuoniumPlayer）。
 * 这个类只是"外壳"：打开 / 关闭、每帧的准备工作、页面切换、把鼠标键盘分发给各部分。
 * 它不依赖任何 Minecraft 版本：各版本的平台层用一个很薄的游戏界面类把事件转发进来
 * （[opened] / [render] / [mouseClicked] / [keyTyped] …），鼠标和窗口大小从 [DangoClient.platform] 读。
 * 具体的东西在 musicui 包里：
 *   [Sidebar] 左侧栏 · [PlayerBar] 底部播放栏 · [LoginDialog] 扫码登录
 *   [GridPage] 主页 / 排行榜歌单墙 · [TracksPage] 歌单 / 搜索 / 队列 · [PartyPage] 一起听 · [SettingsPage] 设置
 *   [Library] 从网易云读来的数据 · [ScreenState] 关掉再打开还保留的界面状态 · [Ui] 绘制工具
 *
 * 菜单字体（Inter）只有拉丁字符，所以中文（歌名、歌手、提示）和图标符号用 Minecraft 自带字体画。
 */
class MusicScreen(
    private val module: NeteaseMusicModule,
    /** 关掉这个界面（由平台层提供） */
    private val closeScreen: () -> Unit,
) {
    private val platform get() = DangoClient.platform

    internal val ui = Ui()
    internal val sidebar = Sidebar(this)
    internal val tracks = TracksPage(this)
    internal val login = LoginDialog(this)
    internal val grid = GridPage(this)
    private val artistPage = ArtistPage(this)
    private val party = PartyPage(this)
    private val settings = SettingsPage(this, module)

    /** 有输入框在打字（搜索、筛选、房间码、代理、设置里改数值 / 颜色）：平台层据此让系统输入法可用 */
    val textInputActive: Boolean
        get() = sidebar.searchFocused || tracks.filterFocused || party.codeFocused || party.proxyFocused || settings.editing
    private val bar = PlayerBar(this, module)

    private var openedAt = 0L
    private var closingAt = 0L
    private var closingAlpha = 1f
    private var closed = false
    private var openKeyHeld = false
    private var lastNanos = 0L
    /** 换页（含换歌单 / 设置换标签 / 新搜索 / 列表↔封面墙）就重新播入场动画 */
    private var pageKey = ""

    /** 界面打开时调用 */
    fun opened() {
        if (closingAt != 0L) return // 关闭期间的窗口尺寸变化不重新播放入场。
        closed = false
        openKeyHeld = platform.isKeyDown(module.openKey.value)
        if (ScreenState.context == null) ScreenState.context = DangoClient.gfx.newCanvas()
        openedAt = System.currentTimeMillis()
        Library.ensureSource()
        NativeAccounts.refreshProfile(MusicSources.selected)
        if (Library.playlists.isEmpty()) Library.reloadPlaylists()
        else if (Library.stale) Library.softRefresh()
        Library.loadRecommend()
    }

    private fun close() {
        if (closingAt != 0L || closed) return
        val now = System.currentTimeMillis()
        closingAlpha = opacity(now)
        closingAt = now
        settings.commitEdit()
        settings.stopDrag()
        tracks.scrollbarUp()
    }

    private fun opacity(now: Long): Float = if (closingAt == 0L) {
        ((now - openedAt) / 180f).coerceIn(0f, 1f)
    } else {
        closingAlpha * (1f - (now - closingAt) / 180f).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- 导航

    internal fun selectSource(source: MusicSource) {
        if (source == MusicSources.selected) return
        module.selectSource(source)
        NativeAccounts.refreshProfile(source)
        sidebar.searchText = ""
        sidebar.searchFocused = false
        ScreenState.lastQuery = ""
        ScreenState.opened = null
        Library.ensureSource()
        go(Page.HOME)
    }

    internal fun go(p: Page) {
        tracks.clearFilter()
        ScreenState.page = p
        ScreenState.backPage = null
        ScreenState.trail.clear()
        ScreenState.artist = null
        tracks.resetScroll()
        grid.resetScroll()
        when (p) {
            Page.HOME -> Library.loadRecommend()
            Page.CHARTS -> Library.loadCharts()
            Page.QUEUE -> if (ScreenState.coverView) tracks.focusCurrentInCoverflow()
            else -> {}
        }
    }

    internal fun goBack() {
        if (ScreenState.page == Page.SETTINGS) {
            ScreenState.page = ScreenState.settingsBack
            return
        }
        val b = ScreenState.backPage
        if (b != null) {
            ScreenState.page = b
            ScreenState.backPage = null
            return
        }
        // 歌手页、从歌手页点进的专辑：退回进来之前的那一层
        val snap = ScreenState.trail.removeLastOrNull()
        if (snap == null) {
            if (ScreenState.page == Page.ARTIST) go(Page.HOME)
            return
        }
        if (ScreenState.opened?.id != snap.opened?.id) tracks.clearFilter()
        ScreenState.page = snap.page
        ScreenState.opened = snap.opened
        ScreenState.backPage = snap.backPage
        ScreenState.artist = snap.artist
        ScreenState.lastQuery = snap.lastQuery
        tracks.resetScroll()
        grid.resetScroll()
    }

    /** 记下现在的界面，之后"返回"能回到这里 */
    private fun pushTrail() {
        val s = ScreenState
        s.trail.addLast(PageSnapshot(s.page, s.opened, s.backPage, s.artist, s.lastQuery))
        while (s.trail.size > 20) s.trail.removeFirst()
    }

    /** 打开歌手页（当前音源的歌手）；已经在看这个歌手就不动 */
    internal fun openArtist(ref: ArtistRef) {
        if (ref.source != MusicSources.selected || !MusicSources.api(ref.source).hasArtists) return
        val cur = ScreenState.artist
        if (ScreenState.page == Page.ARTIST && cur != null && cur.source == ref.source &&
            ((ref.id.isNotEmpty() && cur.id == ref.id) || (ref.id.isEmpty() && cur.name == ref.name))) return
        if (ScreenState.page == Page.SETTINGS) ScreenState.page = ScreenState.settingsBack
        pushTrail()
        tracks.clearFilter()
        ScreenState.page = Page.ARTIST
        ScreenState.artist = ref
        ScreenState.backPage = null
        tracks.resetScroll()
        grid.resetScroll()
    }

    internal fun goBackOrHome() {
        if (ScreenState.backPage != null) goBack() else go(Page.HOME)
    }

    internal fun openPlaylist(p: NeteasePlaylist, from: Page?) {
        // 从歌手页点进专辑：记下歌手页，返回时回到它
        val fromArtist = from == Page.ARTIST
        if (fromArtist) pushTrail()
        if (ScreenState.opened?.id != p.id) tracks.clearFilter()
        ScreenState.opened = p
        ScreenState.page = Page.PLAYLIST
        ScreenState.backPage = if (fromArtist) null else from
        tracks.resetScroll()
        tracks.resetCoverflow()
        // 自己的歌单可能在手机上改过：有缓存也先显示缓存，同时后台重拉
        Library.loadTracks(p.id, refresh = p.owned && Library.tracks(p.id) != null)
    }

    internal fun doSearch() {
        val q = sidebar.searchText.trim()
        if (q.isEmpty()) return
        sidebar.searchFocused = false
        tracks.clearFilter()
        if (ScreenState.page != Page.SEARCH) ScreenState.backPage = ScreenState.page
        ScreenState.page = Page.SEARCH
        tracks.resetScroll()
        tracks.resetCoverflow()
        ScreenState.lastQuery = q
        Library.search(q)
    }

    internal fun toggleSettings() {
        settings.commitEdit()
        blurTextFields()
        settings.bindingKey = null
        if (ScreenState.page == Page.SETTINGS) {
            ScreenState.page = ScreenState.settingsBack
        } else {
            ScreenState.settingsBack = ScreenState.page
            ScreenState.page = Page.SETTINGS
            settings.resetScroll()
        }
    }

    internal fun toggleCoverView() {
        ScreenState.coverView = !ScreenState.coverView
        if (ScreenState.coverView) tracks.focusCurrentInCoverflow()
    }

    /** 取消所有输入框的焦点（搜索、筛选、房间码） */
    internal fun blurTextFields() {
        sidebar.searchFocused = false
        tracks.filterFocused = false
        party.codeFocused = false
        party.commitProxy()
    }

    // ---------------------------------------------------------------- 绘制

    /** 每帧调用 */
    @JvmOverloads
    fun render(menuBackdropReady: Boolean = false) {
        if (closed) return
        val alpha = opacity(System.currentTimeMillis())
        if (closingAt != 0L && alpha <= 0f) {
            closed = true
            if (platform.isKeyDown(module.openKey.value)) module.consumeOpenKey()
            closeScreen()
            return
        }
        val scale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        ui.scale = scale
        ui.lw = platform.windowWidth / scale
        ui.lh = platform.windowHeight / scale
        ui.mx = (platform.mouseX / scale).toInt()
        ui.my = (platform.mouseY / scale).toInt()
        val ctx = ui.ctx
        // 没有世界时原版不会在播放器下面重画背景。先覆盖上一帧，
        // 否则玻璃会反复采样自身，饱和度不断累积，最终变成亮粉色。
        if (platform.player == null && !menuBackdropReady) {
            ctx.begin(false, ui.mx, ui.my)
            try {
                ctx.drawShape(
                    LayoutBounds(0f, 0f, platform.windowWidth.toFloat(), platform.windowHeight.toFloat()),
                    ShapeStyle(Color(0.075f, 0.085f, 0.11f, 1f)),
                )
            } finally {
                ctx.end()
            }
        }
        ctx.begin(true, ui.mx, ui.my)
        try {
            val now = System.nanoTime()
            ui.frameDt = if (lastNanos == 0L) 16f else ((now - lastNanos) / 1e6f).coerceAtMost(50f)
            lastNanos = now
            grid.tick()
            tracks.tick()
            artistPage.tick()
            settings.tick()

            ui.hits.clear()
            settings.beginFrame()
            ui.mt.clear()
            tracks.beginFrame()
            CoverCache.upload()
            login.uploadQr()

            ctx.pushScale(scale, scale, 1f)
            ctx.pushOpacity(alpha)
            ctx.drawShape(LayoutBounds(0f, 0f, ui.lw, ui.lh), ShapeStyle(Color(0f, 0f, 0f, 0.22f)))
            drawWindow(alpha)
            // 播放栏浮在内容上面：先把内容的文字画出来，再把画好的内容截成玻璃背景，
            // 这样列表滚到播放栏下面时会模糊地透出来，而不是被整个盖住
            ctx.popOpacity()
            ctx.popTransform()
            ctx.noScissor()
            ui.mt.flush(scale)
            ctx.recaptureBackdrop()
            ctx.pushScale(scale, scale, 1f)
            ctx.pushOpacity(alpha)
            drawBar()
            ctx.popOpacity()
            ctx.popTransform()
            ctx.noScissor()
            ui.mt.flush(scale)

            if (login.visible) {
                ctx.pushScale(scale, scale, 1f)
                ctx.pushOpacity(alpha)
                login.draw()
                ctx.popOpacity()
                ctx.popTransform()
                ui.mt.flush(scale)
            }
        } finally {
            ctx.end()
        }
    }

    // ---------------------------------------------------------------- 窗口位置和大小

    /** 拖动窗口时改的是哪里 */
    private enum class Grab { MOVE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /** 这一帧窗口的位置（不含打开动画的位移），拖动时用 */
    private var winRect = LayoutBounds()
    private var grab: Grab? = null
    private var grabStart = LayoutBounds()
    private var grabX = 0
    private var grabY = 0
    /** 拖动过程中的窗口；松开时存进设置 */
    private var dragWin: LayoutBounds? = null
    private var lastMoveClick = 0L

    /** 默认大小：几乎铺满屏幕 */
    private fun defaultWin() = LayoutBounds(26f, 20f, ui.lw - 52f, ui.lh - 40f)

    /** 玩家调过的窗口（按现在的屏幕换算回来，不小于最小尺寸、不超出屏幕） */
    private fun savedWin(): LayoutBounds {
        val f = module.windowFrame() ?: return defaultWin()
        return fitWin(LayoutBounds(f[0] * ui.lw, f[1] * ui.lh, f[2] * ui.lw, f[3] * ui.lh))
    }

    private fun fitWin(b: LayoutBounds): LayoutBounds {
        val w = b.width.coerceIn(min(MIN_WIN_W, ui.lw), ui.lw)
        val h = b.height.coerceIn(min(MIN_WIN_H, ui.lh), ui.lh)
        return LayoutBounds(b.x.coerceIn(0f, ui.lw - w), b.y.coerceIn(0f, ui.lh - h), w, h)
    }

    /** 鼠标在窗口的哪条边 / 哪个角上（可以拖着改大小），或者顶部那条能拖着移动的地方；都不是返回 null */
    private fun grabAt(mx: Int, my: Int): Grab? {
        val w = winRect
        if (w.width <= 0f) return null
        val e = EDGE_GRAB
        val inX = mx >= w.x - e && mx <= w.right + e
        val inY = my >= w.y - e && my <= w.bottom + e
        if (!inX || !inY) return null
        val left = mx <= w.x + e
        val right = mx >= w.right - e
        val top = my <= w.y + e
        val bottom = my >= w.bottom - e
        return when {
            top && left -> Grab.TOP_LEFT
            top && right -> Grab.TOP_RIGHT
            bottom && left -> Grab.BOTTOM_LEFT
            bottom && right -> Grab.BOTTOM_RIGHT
            left -> Grab.LEFT
            right -> Grab.RIGHT
            top -> Grab.TOP
            bottom -> Grab.BOTTOM
            // 内容区上方那条空白（侧栏右边、页面标题上面）：拖着移动窗口
            my <= w.y + MOVE_STRIP && mx >= w.x + 10f + 224f -> Grab.MOVE
            else -> null
        }
    }

    /** 按下鼠标：按在边 / 角 / 顶部空白上就开始拖，返回 true（这次点击不再交给别的东西） */
    private fun beginWindowDrag(mx: Int, my: Int): Boolean {
        val g = grabAt(mx, my) ?: return false
        if (g == Grab.MOVE) {
            val now = System.currentTimeMillis()
            // 双击顶部空白：恢复默认大小
            if (now - lastMoveClick < 350L) {
                lastMoveClick = 0L
                module.setWindowFrame(null)
                return true
            }
            lastMoveClick = now
        }
        grab = g
        grabStart = winRect
        grabX = mx
        grabY = my
        dragWin = winRect
        return true
    }

    private fun dragWindow(mx: Int, my: Int) {
        val g = grab ?: return
        val s = grabStart
        val dx = (mx - grabX).toFloat()
        val dy = (my - grabY).toFloat()
        var x1 = s.x; var y1 = s.y; var x2 = s.right; var y2 = s.bottom
        if (g == Grab.MOVE) {
            x1 += dx; x2 += dx; y1 += dy; y2 += dy
            val w = s.width; val h = s.height
            val x = x1.coerceIn(0f, ui.lw - w)
            val y = y1.coerceIn(0f, ui.lh - h)
            dragWin = LayoutBounds(x, y, w, h)
            return
        }
        val minW = min(MIN_WIN_W, ui.lw)
        val minH = min(MIN_WIN_H, ui.lh)
        if (g == Grab.LEFT || g == Grab.TOP_LEFT || g == Grab.BOTTOM_LEFT) x1 = (s.x + dx).coerceIn(0f, x2 - minW)
        if (g == Grab.RIGHT || g == Grab.TOP_RIGHT || g == Grab.BOTTOM_RIGHT) x2 = (s.right + dx).coerceIn(x1 + minW, ui.lw)
        if (g == Grab.TOP || g == Grab.TOP_LEFT || g == Grab.TOP_RIGHT) y1 = (s.y + dy).coerceIn(0f, y2 - minH)
        if (g == Grab.BOTTOM || g == Grab.BOTTOM_LEFT || g == Grab.BOTTOM_RIGHT) y2 = (s.bottom + dy).coerceIn(y1 + minH, ui.lh)
        dragWin = LayoutBounds(x1, y1, x2 - x1, y2 - y1)
    }

    /** 松开鼠标：把拖好的窗口存起来（按占屏幕的比例存） */
    private fun endWindowDrag() {
        val w = dragWin
        if (grab != null && w != null && ui.lw > 0f && ui.lh > 0f) {
            module.setWindowFrame(floatArrayOf(w.x / ui.lw, w.y / ui.lh, w.width / ui.lw, w.height / ui.lh))
        }
        grab = null
        dragWin = null
    }

    /** 鼠标移到能改大小的边 / 角上时边框微微发亮（顶部那条移动用的空白不画任何东西） */
    private fun drawWindowHints() {
        val w = winRect
        val hover = grab ?: grabAt(ui.mx, ui.my)
        val k = edgeGlow.tick(if (hover != null && hover != Grab.MOVE) 1f else 0f).coerceIn(0f, 1f)
        if (k > 0.01f) ui.ctx.drawShape(w, ShapeStyle(Color.TRANSPARENT, Theme.GLASS_WINDOW.radius, 1.5f, Color(1f, 1f, 1f, 0.35f * k)))
    }

    private val edgeGlow = me.sorihiko.dango.musicui.Spring(300f, 26f)

    private fun drawWindow(alpha: Float) {
        val base = dragWin ?: savedWin()
        winRect = base
        val win = LayoutBounds(base.x, base.y + (1f - alpha) * 12f, base.width, base.height)
        ui.ctx.drawGlass(win, Theme.GLASS_WINDOW)

        val pad = 10f
        val sideW = 224f
        val barH = 88f
        val side = LayoutBounds(win.x + pad, win.y + pad, sideW, win.height - pad * 2)
        val mainX = side.right + pad + 8f
        val mainW = win.right - pad - 8f - mainX
        // 播放栏浮在内容上面：左右和底部留出间距；内容一直延伸到窗口底部，滚动时从播放栏后面经过
        val barX = mainX + 2f
        val barRect = LayoutBounds(barX, win.bottom - pad - 8f - barH, win.right - pad - 12f - barX, barH)
        val content = LayoutBounds(mainX, win.y + pad + 14f, mainW, win.bottom - pad - (win.y + pad + 14f))

        ui.popup = bar.popupRect.takeIf { it.width > 0f }
        sidebar.draw(side)
        ui.occluder = barRect
        ui.bottomInset = content.bottom - barRect.y + 10f
        drawContent(content)
        ui.occluder = null
        ui.popup = null
        this.barRect = barRect
    }

    /** 这一帧播放栏的位置（[drawWindow] 里算好，内容画完之后再画播放栏） */
    private var barRect = LayoutBounds()

    private fun drawBar() {
        // 播放栏画在最上面，点击也要先轮到它（点到的是第一个命中的区域）
        val before = ui.hits.size
        bar.draw(barRect)
        val barHits = ui.hits.subList(before, ui.hits.size).toList()
        repeat(barHits.size) { ui.hits.removeAt(ui.hits.size - 1) }
        // 播放栏的空白处也要挡住点击，不然会点到底下那首歌
        ui.hits.addAll(0, barHits + listOf(barRect to {}))
        drawWindowHints()
    }

    private fun drawContent(area: LayoutBounds) {
        // 底部小字只在有提示（读取失败等）时显示
        val foot = I18n.message(Library.status)
        // 提示文字放在播放栏上面，不被它挡住
        if (foot.isNotEmpty()) ui.text(ui.trim(foot, area.width, 10f, Theme.FONT_SEMIBOLD), area.x, area.bottom - ui.bottomInset - 13f, 10f, RED, Theme.FONT_SEMIBOLD)
        val body = LayoutBounds(area.x, area.y, area.width, area.height - (if (foot.isNotEmpty()) 18f else 4f))
        // 滚轮区域：哪一页画了，哪一页才有
        grid.area = LayoutBounds()
        tracks.area = LayoutBounds()
        settings.area = LayoutBounds()
        artistPage.area = LayoutBounds()

        val s = ScreenState
        // 设置页的标签切换有自己的过渡动画，不算换页（否则整页会从透明重新淡入，看起来闪一下）
        // 列表 / 封面墙切换有自己的过渡动画，不算换页（否则整页会从透明重新淡入，看起来闪一下）
        val key = "${s.page}|${s.opened?.id}|${s.lastQuery}|${s.artist?.source}|${s.artist?.id}|${s.artist?.name}"
        if (key != pageKey) {
            pageKey = key
            ui.pageAt = System.currentTimeMillis()
            tracks.onPageChanged()
        }

        when (s.page) {
            Page.HOME -> {
                val rec = Library.recommend
                val items = if (rec.isNullOrEmpty()) Library.playlists else rec
                val label = if (rec.isNullOrEmpty()) I18n.text("Your playlists") else I18n.text("Recommended playlists")
                grid.draw(body, I18n.text("Welcome to Dango Music!"), "$label · ${items.size}", items, Library.recommendLoading && items.isEmpty())
            }
            Page.CHARTS -> {
                val c = Library.charts ?: emptyList()
                grid.draw(body, I18n.text("Charts"), I18n.format("%d charts", c.size), c, Library.chartsLoading)
            }
            Page.PARTY -> ui.itemAnim(0) { party.draw(LayoutBounds(body.x, body.y, body.width, body.height - ui.bottomInset)) }
            Page.SETTINGS -> settings.draw(body)
            Page.ARTIST -> artistPage.draw(body)
            else -> tracks.draw(body)
        }
    }

    // ---------------------------------------------------------------- 鼠标

    fun mouseClicked(mouseButton: Int) {
        if (closingAt != 0L || closed) return
        val mx = ui.mx
        val my = ui.my
        if (login.visible) {
            login.mouseClicked(mx, my, mouseButton)
            return
        }
        if (mouseButton == 0 && beginWindowDrag(mx, my)) return
        if (mouseButton == 0 && settings.resetKeyAt(mx, my)) return
        if (settings.bindMouse(mouseButton)) return
        val mouseKey = Keys.mouse(mouseButton)
        if (mouseKey == module.openKey.value) {
            if (!openKeyHeld) {
                openKeyHeld = true
                module.consumeOpenKey()
                close()
            }
            return
        }
        if (module.handleHotkey(mouseKey)) return
        // 鼠标侧键 / 右键：返回上一页
        if (mouseButton == 1 || mouseButton == 3) {
            goBack()
            return
        }
        if (mouseButton != 0) return
        if (bar.mouseDown(mx, my)) return
        if (tracks.scrollbarDown(mx, my)) return
        val hit = ui.hits.firstOrNull { it.first.contains(mx, my) }
        settings.onClickElsewhere(hit)
        if (hit == null || hit.first !== sidebar.searchRect) sidebar.searchFocused = false
        if (hit == null || !sidebar.isMenuHit(hit.first)) sidebar.menuOpen = false
        if (hit == null || hit.first !== tracks.filterRect) tracks.filterFocused = false
        if (hit == null || hit.first !== party.codeRect) party.codeFocused = false
        if (hit == null || hit.first !== party.proxyRect) party.commitProxy()
        hit?.second?.invoke()
    }

    /** 按住鼠标拖动时调用 */
    fun mouseDragged() {
        if (closingAt != 0L || closed) return
        if (grab != null) {
            dragWindow(ui.mx, ui.my)
            return
        }
        if (tracks.sbDragging) {
            tracks.scrollbarDrag((platform.mouseY / ui.scale).toInt())
            return
        }
        if (!login.visible) bar.mouseDrag(ui.mx, ui.my)
    }

    fun mouseReleased() {
        if (module.openKey.value < 0) openKeyHeld = false
        endWindowDrag()
        bar.mouseUp()
        tracks.scrollbarUp()
        settings.stopDrag()
    }

    /** 滚轮：[wheel] 正数向上；(x, y) 是滚动时鼠标的窗口像素位置 */
    fun scroll(wheel: Int, x: Int, y: Int) {
        if (closingAt != 0L || closed) return
        if (wheel == 0 || login.visible) return
        val lx = (x / ui.scale).toInt()
        val ly = (y / ui.scale).toInt()
        // 按住 Ctrl 滚得快十倍
        val step = (if (wheel > 0) -3 else 3) * (if (platform.ctrlDown) 10 else 1)
        when {
            bar.add.open && bar.add.panelRect.contains(lx, ly) -> bar.add.scroll(wheel)
            settings.area.contains(lx, ly) -> settings.scroll(wheel)
            sidebar.listArea.contains(lx, ly) -> sidebar.scrollBy(step)
            grid.area.contains(lx, ly) -> grid.scroll(wheel)
            artistPage.area.contains(lx, ly) -> artistPage.scroll(wheel)
            tracks.area.contains(lx, ly) -> tracks.scroll(wheel, step)
        }
    }

    // ---------------------------------------------------------------- 键盘

    /** 按键：[keyCode] 是 [Keys] 键码 */
    fun keyTyped(typedChar: Char, keyCode: Int) {
        if (closingAt != 0L || closed) {
            if (keyCode == module.openKey.value) module.consumeOpenKey()
            return
        }
        if (login.visible) {
            login.keyTyped(keyCode)
            return
        }
        // 正在输入 / 录按键的地方先拿走按键
        if (settings.keyTyped(typedChar, keyCode)) return
        if (sidebar.keyTyped(typedChar, keyCode)) return
        if (party.keyTyped(typedChar, keyCode)) return
        if (tracks.filterKeyTyped(typedChar, keyCode)) return
        if (keyCode != Keys.NONE && keyCode == module.openKey.value) {
            if (!openKeyHeld) {
                openKeyHeld = true
                module.consumeOpenKey()
                close()
            }
            return
        }
        // 自己设的快捷键在播放器里也能用
        if (keyCode != Keys.ESCAPE && keyCode != module.openKey.value && module.handleHotkey(keyCode)) return
        if (tracks.shortcutBefore(keyCode)) return
        when {
            // 其它页面 Ctrl+F 是全网搜索
            platform.ctrlDown && keyCode == Keys.F -> sidebar.searchFocused = true
            keyCode == Keys.SPACE -> MusicPlayer.togglePause()
            keyCode == Keys.BACK -> goBack()
            tracks.coverflowKey(keyCode) -> {}
            keyCode == Keys.RIGHT -> MusicPlayer.seek(MusicPlayer.positionMs + 5000)
            keyCode == Keys.LEFT -> MusicPlayer.seek(MusicPlayer.positionMs - 5000)
            keyCode == Keys.ESCAPE -> close()
        }
    }

    /** 松开打开键后，下一次按下才是关闭；长按重复事件不算新按下。 */
    fun keyReleased(keyCode: Int) {
        if (keyCode == module.openKey.value) openKeyHeld = false
    }
}

/** 播放器窗口最小多大（逻辑像素）：再小侧栏和播放栏就排不下了 */
private const val MIN_WIN_W = 880f
private const val MIN_WIN_H = 480f
/** 窗口边缘里外多宽算"按在边上"（可以拖着改大小） */
private const val EDGE_GRAB = 6f
/** 窗口顶部多高的空白能拖着移动 */
private const val MOVE_STRIP = 22f
