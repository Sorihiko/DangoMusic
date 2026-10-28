package me.sorihiko.dango.music

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.musicui.GridPage
import me.sorihiko.dango.musicui.Library
import me.sorihiko.dango.musicui.LoginDialog
import me.sorihiko.dango.musicui.Page
import me.sorihiko.dango.musicui.PartyPage
import me.sorihiko.dango.musicui.PlayerBar
import me.sorihiko.dango.musicui.RED
import me.sorihiko.dango.musicui.ScreenState
import me.sorihiko.dango.musicui.SettingsPage
import me.sorihiko.dango.musicui.Sidebar
import me.sorihiko.dango.musicui.SaltPanel
import me.sorihiko.dango.musicui.TracksPage
import me.sorihiko.dango.musicui.Ui
import me.sorihiko.dango.playback.PlaybackMode
import me.sorihiko.dango.playback.PlaybackSessions
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
    private val grid = GridPage(this)
    private val party = PartyPage(this)
    private val settings = SettingsPage(this, module)
    private val saltPanel = SaltPanel(ui, module)
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

    internal val saltAvailable: Boolean get() = module.saltMode.get()

    internal fun selectSalt() {
        if (!module.selectSalt()) {
            if (module.saltSwitchNote.isNotEmpty()) Library.showStatus(module.saltSwitchNote)
            return
        }
        sidebar.searchFocused = false
        go(Page.HOME)
    }

    internal fun selectSource(source: MusicSource) {
        if (source == MusicSources.selected && PlaybackSessions.mode == PlaybackMode.BUILT_IN) return
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
        val b = ScreenState.backPage ?: return
        ScreenState.page = b
        ScreenState.backPage = null
    }

    internal fun goBackOrHome() {
        if (ScreenState.backPage != null) goBack() else go(Page.HOME)
    }

    internal fun openPlaylist(p: NeteasePlaylist, from: Page?) {
        if (ScreenState.opened?.id != p.id) tracks.clearFilter()
        ScreenState.opened = p
        ScreenState.page = Page.PLAYLIST
        ScreenState.backPage = from
        tracks.resetScroll()
        tracks.resetCoverflow()
        Library.loadTracks(p.id)
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

    private fun drawWindow(alpha: Float) {
        // 几乎铺满屏幕，和 MuoniumPlayer 一样
        val mX = 26f
        val mY = 20f
        val win = LayoutBounds(mX, mY + (1f - alpha) * 12f, ui.lw - mX * 2, ui.lh - mY * 2)
        ui.ctx.drawGlass(win, Theme.GLASS_WINDOW)

        val pad = 10f
        val sideW = 224f
        val barH = 74f
        val side = LayoutBounds(win.x + pad, win.y + pad, sideW, win.height - pad * 2)
        val mainX = side.right + pad + 8f
        val mainW = win.right - pad - 8f - mainX
        val barRect = LayoutBounds(side.right + pad, win.bottom - pad - barH, win.right - pad - (side.right + pad), barH)
        val content = LayoutBounds(mainX, win.y + pad + 14f, mainW, barRect.y - 10f - (win.y + pad + 14f))

        sidebar.draw(side)
        drawContent(content)
        bar.draw(barRect)
    }

    private fun drawContent(area: LayoutBounds) {
        // 底部小字只在有提示（读取失败等）时显示
        val foot = I18n.message(Library.status)
        if (foot.isNotEmpty()) ui.text(ui.trim(foot, area.width, 10f, Theme.FONT_SEMIBOLD), area.x, area.bottom - 11f, 10f, RED, Theme.FONT_SEMIBOLD)
        val body = LayoutBounds(area.x, area.y, area.width, area.height - (if (foot.isNotEmpty()) 18f else 4f))
        // 滚轮区域：哪一页画了，哪一页才有
        grid.area = LayoutBounds()
        tracks.area = LayoutBounds()
        settings.area = LayoutBounds()

        val s = ScreenState
        // 设置页的标签切换有自己的过渡动画，不算换页（否则整页会从透明重新淡入，看起来闪一下）
        val key = "${s.page}|${s.opened?.id}|${s.coverView}|${s.lastQuery}"
        if (key != pageKey) {
            pageKey = key
            ui.pageAt = System.currentTimeMillis()
            tracks.onPageChanged()
        }

        when (s.page) {
            Page.HOME -> {
                // Salt 模式：首页是控制面板（曲库和引擎都在 Salt Player）
                if (PlaybackSessions.mode == PlaybackMode.SALT) {
                    ui.itemAnim(0) { saltPanel.draw(body) }
                } else {
                    val rec = Library.recommend
                    val items = if (rec.isNullOrEmpty()) Library.playlists else rec
                    val label = if (rec.isNullOrEmpty()) I18n.text("Your playlists") else I18n.text("Recommended playlists")
                    grid.draw(body, I18n.text("Welcome to Dango Music!"), "$label · ${items.size}", items, Library.recommendLoading && items.isEmpty())
                }
            }
            Page.CHARTS -> {
                val c = Library.charts ?: emptyList()
                grid.draw(body, I18n.text("Charts"), I18n.format("%d charts", c.size), c, Library.chartsLoading)
            }
            Page.PARTY -> ui.itemAnim(0) { party.draw(body) }
            Page.SETTINGS -> settings.draw(body)
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
        if (ScreenState.page == Page.HOME && PlaybackSessions.mode == PlaybackMode.SALT && saltPanel.mouseDown(mx, my)) return
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
        if (tracks.sbDragging) {
            tracks.scrollbarDrag((platform.mouseY / ui.scale).toInt())
            return
        }
        if (!login.visible) bar.mouseDrag(ui.mx, ui.my)
    }

    fun mouseReleased() {
        if (module.openKey.value < 0) openKeyHeld = false
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
            settings.area.contains(lx, ly) -> settings.scroll(wheel)
            sidebar.listArea.contains(lx, ly) -> sidebar.scrollBy(step)
            grid.area.contains(lx, ly) -> grid.scroll(wheel)
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
