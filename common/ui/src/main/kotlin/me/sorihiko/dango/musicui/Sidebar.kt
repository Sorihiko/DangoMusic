package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.music.MusicSources
import me.sorihiko.dango.music.NativeAccounts
import me.sorihiko.dango.party.ListenParty
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.playback.PlaybackMode
import me.sorihiko.dango.playback.PlaybackSessions
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.GlassStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.min

/**
 * 左侧玻璃侧栏：搜索框 / 主页 · 排行榜 · 正在播放 · 一起听 / 我的歌单 / 设置 / 账号。
 * 选中项下面有一块"果冻"选中块：上下两条边分开动，前面那条边 220ms、后面那条 380ms，拉伸着滑过去。
 */
internal class Sidebar(private val screen: MusicScreen) {
    private val ui = screen.ui

    // 搜索框
    var searchText = ""
    var searchFocused = false
    var searchRect = LayoutBounds()
        private set

    // 歌单列表：滚动目标（行）和平滑后的当前位置
    var listArea = LayoutBounds()
        private set
    private var scroll = 0
    private var scrollPos = 0f

    // 选中块：本帧算出的目标，下一帧往那里动
    private var selKey = ""
    private var selTarget: LayoutBounds? = null
    private var pillKey = ""
    private var pillTop = 0f
    private var pillBot = 0f
    private var pillAlpha = 0f
    private var pillFromTop = 0f
    private var pillFromBot = 0f
    private var pillAt = 0L
    private var pillDown = false

    private val MENU_ITEM_H = 30f

    /** 音源菜单的液态玻璃：比侧栏更暗、更磨砂，保证上面的字看得清 */
    private val MENU_GLASS = GlassStyle(
        radius = 14f,
        tint = Color(0.05f, 0.05f, 0.07f, 0.55f),
        maxLuma = 0.22f,
        saturation = 1.4f,
        frost = 0.9f,
        refraction = 12f,
        bevel = 12f,
        dispersion = 0.3f,
        highlight = 1.1f,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.30f), 18f, Vec2(0f, -4f)),
    )

    /** 底部账号栏的音源菜单是否展开 */
    var menuOpen = false
    /** 本帧音源菜单和打开它的按钮的区域（点到这些以外的地方就收起菜单） */
    private val menuRects = mutableListOf<LayoutBounds>()

    fun isMenuHit(b: LayoutBounds): Boolean = menuRects.any { it === b }

    // 音源菜单的弹簧动画：sw 横向、sh 纵向的展开程度（0 收起，1 展开；弹出时会略微冲过 1 再弹回来）
    private var sw = 0f
    private var vw = 0f
    private var sh = 0f
    private var vh = 0f
    private var frameNanos = 0L

    // 搜索框：聚焦程度（聚焦时略微冲过 1 再回来）、点一下 / 回车时的轻微回弹
    private val searchFocus = Spring(260f, 17f)
    private val searchPress = Spring(420f, 13f)
    // 设置按钮：选中程度（颜色渐变）、点击时先缩一下再弹回来
    private val settingsOn = Spring(240f, 24f)
    private val settingsPress = Spring(420f, 13f)
    /** 上一帧音源按钮的位置：菜单从这里"长"出来、缩回这里 */
    private var chipRect = LayoutBounds()

    /** 菜单正在显示（展开中、展开着或正在收起） */
    private val menuVisible: Boolean get() = menuOpen || sw > 0.004f || sh > 0.004f

    fun scrollBy(rows: Int) {
        scroll += rows
    }

    fun draw(side: LayoutBounds) {
        val ctx = ui.ctx
        val page = ScreenState.page
        ctx.drawGlass(side, Theme.GLASS_SIDEBAR)
        drawSelectionPill(side)
        var newKey = ""
        var newTarget: LayoutBounds? = null
        val x = side.x + 12f
        val iw = side.width - 24f
        var y = side.y + 12f

        menuRects.clear()
        val now = System.nanoTime()
        val dt = if (frameNanos == 0L) 0.016f else ((now - frameNanos) / 1e9f).coerceIn(0f, 0.05f)
        frameNanos = now
        stepMenu(dt)
        searchFocus.step(if (searchFocused) 1f else 0f, dt)
        searchPress.step(0f, dt)
        settingsOn.step(if (page == Page.SETTINGS) 1f else 0f, dt)
        settingsPress.step(0f, dt)
        val menuNow = if (menuVisible) menuRect(side) else null
        // 搜索框：放大镜 + 输入。点击区域固定，动画只改画出来的样子：
        // 聚焦时框微微鼓起、外面一圈淡红光晕、描边和放大镜渐变成红色，提示文字往右滑走淡出
        searchRect = LayoutBounds(x, y, iw, 32f)
        val f = searchFocus.x
        val fc = f.coerceIn(0f, 1f)
        val grow = f * 2f + searchPress.x * 3f
        val box = LayoutBounds(x - grow, y - grow * 0.5f, iw + grow * 2f, 32f + grow)
        val br = 10f + grow * 0.5f
        if (fc > 0.01f) ctx.drawShape(box.expand(3f * fc, 3f * fc), ShapeStyle(RED.copy(a = 0.12f * fc), br + 3f * fc))
        ui.card(box, mix(Theme.CARD, Theme.CARD_HOVER, fc), br)
        if (fc > 0.01f) ctx.drawShape(box, ShapeStyle(Color(0f, 0f, 0f, 0f), br, 1f + 0.4f * fc, RED.copy(a = RED.a * fc)))
        val glass = mix(Theme.TEXT_SECONDARY, RED, fc)
        val my = y - grow * 0.5f + (32f + grow) / 2f
        ctx.drawShape(LayoutBounds(x + 11f, my - 6f, 9f, 9f), ShapeStyle(Color(0f, 0f, 0f, 0f), 4.5f, 1.4f, glass))
        ctx.drawShape(LayoutBounds(x + 18.5f, my + 2f, 4.5f, 1.6f), ShapeStyle(glass, 0.8f))
        val caret = if (searchFocused && caretVisible()) "|" else ""
        val maxW = searchRect.right - 10f - (x + 30f)
        if (searchText.isEmpty()) {
            val fade = (1f - fc * 1.3f).coerceIn(0f, 1f)
            if (fade > 0.01f) {
                val ph = I18n.text("Search songs...")
                ui.text(ui.trim(ph, maxW - 12f * fc, 12f), x + 30f + 12f * fc, my - 6f, 12f, Theme.TEXT_TERTIARY.copy(a = Theme.TEXT_TERTIARY.a * fade))
            }
            if (caret.isNotEmpty()) ui.text(caret, x + 30f, my - 6f, 12f, Theme.TEXT_PRIMARY)
        } else {
            var visible = searchText + caret
            while (visible.length > 1 && ui.width(visible, 12f) > maxW) visible = visible.substring(1)   // 太长时显示末尾
            ui.text(visible, x + 30f, my - 6f, 12f, Theme.TEXT_PRIMARY)
        }
        ui.hits += searchRect to {
            if (!searchFocused) searchPress.v -= 6f
            searchFocused = true
        }
        y += 42f

        fun section(label: String) {
            ui.text(label, x + 2f, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 16f
        }

        fun nav(glyph: String, label: String, active: Boolean, redIcon: Boolean, action: () -> Unit) {
            val row = LayoutBounds(side.x + 6f, y, side.width - 12f, 30f)
            if (active) {
                newKey = "nav:$glyph"
                newTarget = row
            } else if (ui.hover(row)) {
                ui.card(row, Theme.CARD_HOVER, 9f)
            }
            ui.icon(glyph, row.x + 18f, row.y + 15f, 12f, if (active || redIcon) RED else Theme.TEXT_SECONDARY)
            ui.text(label, row.x + 34f, row.y + 10f, 12f, if (active) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
            ui.hits += row to action
            y += 32f
        }

        section("DANGO MUSIC")
        nav("⌂", I18n.text("Home"), page == Page.HOME, true) { screen.go(Page.HOME) }
        section(I18n.text("DISCOVER"))
        nav("▤", I18n.text("Charts"), page == Page.CHARTS, false) { screen.go(Page.CHARTS) }
        nav("♫", I18n.text("Now Playing"), page == Page.QUEUE, false) { screen.go(Page.QUEUE) }
        nav("♬", if (ListenParty.role == ListenParty.Role.NONE) I18n.text("Listen Together") else I18n.text("Listen Together · ") + ListenParty.members.size,
            page == Page.PARTY, ListenParty.role != ListenParty.Role.NONE) { screen.go(Page.PARTY) }
        y += 4f
        section(I18n.text("MY PLAYLISTS"))

        // 歌单列表（底部留出按钮和账号的位置）
        val rowH = 30f
        val footerH = 92f
        listArea = LayoutBounds(side.x + 6f, y, side.width - 12f, side.bottom - footerH - y)
        val rows = max(1, (listArea.height / rowH).toInt())
        val list = Library.playlists
        scroll = scroll.coerceIn(0, max(0, list.size - rows))
        scrollPos = ui.easeScroll(scrollPos, scroll, rows * 3)
        val firstOwned = list.indexOfFirst { it.owned }
        val first = kotlin.math.floor(scrollPos).toInt()
        val frac = scrollPos - first
        // 音源菜单打开时，歌单只画到菜单上沿：文字是最后统一画的，不让出来会透过菜单显示
        val listShown = if (menuNow != null) LayoutBounds(listArea.x, listArea.y, listArea.width,
            max(0f, kotlin.math.min(listArea.bottom, menuNow.y - 4f) - listArea.y)) else listArea
        ui.beginClip(listShown)
        for (r in 0..rows) {
            val i = first + r
            if (i < 0) continue
            if (i >= list.size) break
            val p = list[i]
            val row = LayoutBounds(listArea.x, listArea.y + (r - frac) * rowH, listArea.width, rowH - 2f)
            val active = page == Page.PLAYLIST && ScreenState.opened?.id == p.id
            if (active) {
                newKey = "pl:${p.id}"
                newTarget = row
            } else if (ui.hover(row)) {
                ui.card(row, Theme.CARD_HOVER, 9f)
            }
            val liked = i == firstOwned   // 第一个自己的歌单就是"我喜欢的音乐"
            ui.icon(if (liked) "♥" else "≡", row.x + 18f, row.y + 14f, 12f, if (liked || active) RED else Theme.TEXT_SECONDARY)
            val c = if (active) Theme.TEXT_PRIMARY else if (p.owned) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY
            ui.text(ui.trim(p.name, row.right - 8f - (row.x + 34f), 12f), row.x + 34f, row.y + 9f, 12f, c)
            ui.hits += row.intersect(listShown) to { screen.openPlaylist(p, null) }
        }
        ui.endClip()
        if (list.isEmpty() && (menuNow == null || listArea.y + 18f < menuNow.y)) {
            val loggedIn = if (MusicSources.selected == MusicSource.NETEASE) NeteaseApi.loggedIn else NativeAccounts.get(MusicSources.selected) != null
            val msg = if (Library.playlistsLoading) I18n.text("Loading...") else if (loggedIn) I18n.text("No playlists") else I18n.text("Log in to see your playlists")
            ui.text(msg, x + 2f, listArea.y + 6f, 11f, Theme.TEXT_TERTIARY)
            if (Library.playlistsLoading) ui.progressBar(LayoutBounds(x + 2f, listArea.y + 24f, iw - 4f, 3f), null)
        }

        // 设置按钮（歌单会在打开播放器时自动刷新，不需要刷新按钮）；被音源菜单盖住时不画
        val settingsY = side.bottom - footerH + 8f
        if (menuNow == null || menuNow.y > settingsY + 32f) drawSettingsButton(x, iw, settingsY, page)

        drawAccount(side)
        if (menuNow != null) drawSourceMenu(side, menuRect(side))

        // 下一帧选中块往这里动
        selKey = newKey
        selTarget = newTarget
    }

    /** 设置按钮：点一下先缩一下再弹回来，选中的红色渐入渐出（点击区域固定，动画只改画出来的样子） */
    private fun drawSettingsButton(x: Float, iw: Float, by: Float, page: Page) {
        val ctx = ui.ctx
        val sb = LayoutBounds(x, by, iw, 32f)
        val o = settingsOn.x.coerceIn(0f, 1f)
        val k = 1f + settingsPress.x * 0.12f
        val b = LayoutBounds(sb.x + sb.width * (1f - k) / 2f, sb.y + sb.height * (1f - k) / 2f, sb.width * k, sb.height * k)
        val base = if (ui.hover(sb)) Theme.CARD_HOVER else Theme.CARD
        ui.card(b, mix(base, RED.copy(a = 0.22f), o), 10f * k)
        if (o > 0.01f) ctx.drawShape(b, ShapeStyle(Color(0f, 0f, 0f, 0f), 10f * k, 1f, RED.copy(a = 0.6f * o)))
        val sCol = mix(Theme.TEXT_PRIMARY, RED, o)
        val sLabel = I18n.text("Settings")
        val size = 12f * k
        val sw = 14f * k + 6f * k + ui.width(sLabel, size, Theme.FONT_SEMIBOLD)
        val sx = b.x + (b.width - sw) / 2f
        val cy = b.y + b.height / 2f
        ui.icon("⚙", sx + 7f * k, cy, 14f * k, sCol)
        ui.text(sLabel, sx + 20f * k, cy - size * 0.39f - 0.5f, size, sCol, Theme.FONT_SEMIBOLD)
        ui.hits += sb to {
            settingsPress.v -= 7f
            screen.toggleSettings()
        }
    }

    private fun mix(a: Color, b: Color, t: Float) =
        Color(lerp(a.r, b.r, t), lerp(a.g, b.g, t), lerp(a.b, b.b, t), lerp(a.a, b.a, t))

    private fun loggedIn(source: MusicSource): Boolean =
        if (source == MusicSource.NETEASE) NeteaseApi.loggedIn else NativeAccounts.get(source) != null

    /** 账号栏：头像 + 昵称（没登录时是"登录 xx"），右边是当前音源，点开菜单切换音源 / 退出登录 */
    private fun drawAccount(side: LayoutBounds) {
        val ub = LayoutBounds(side.x + 6f, side.bottom - 46f, side.width - 12f, 40f)
        val av = LayoutBounds(ub.x + 6f, ub.y + 6f, 28f, 28f)
        val source = MusicSources.selected
        val salt = PlaybackSessions.mode == PlaybackMode.SALT
        val native = source != MusicSource.NETEASE
        val account = if (native) NativeAccounts.get(source) else null

        // 右边的音源按钮：当前音源名 + ⇄；登记在整行前面，点它只开关菜单
        val chipLabel = I18n.text(if (salt) "Salt Player" else source.label)
        val chipW = ui.width(chipLabel, 10f, Theme.FONT_MEDIUM) + 26f
        val chip = LayoutBounds(ub.right - chipW - 6f, ub.y + 9f, chipW, 22f)
        ui.card(chip, if (menuOpen || ui.hover(chip)) Theme.CARD_HOVER else Theme.CARD, 7f)
        ui.text(chipLabel, chip.x + 8f, chip.y + 7f, 10f, if (menuOpen) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
        ui.icon("⇄", chip.right - 10f, chip.y + 11f, 10f, Theme.TEXT_SECONDARY)
        chipRect = chip
        menuRects += chip
        ui.hits += chip to { menuOpen = !menuOpen }

        val textW = chip.x - 8f - (av.right + 10f)
        if (salt) {
            ui.ctx.drawShape(av, ShapeStyle(Theme.TRACK, 14f))
            ui.icon("♫", av.x + 14f, av.y + 14f, 12f, Theme.TEXT_SECONDARY)
            ui.text(ui.trim(I18n.text("Local player"), textW, 12f), av.right + 10f, ub.y + 14f, 12f, Theme.TEXT_PRIMARY)
        } else if (if (native) account != null else NeteaseApi.loggedIn) {
            val aurl = if (native) account!!.avatarUrl else NeteaseApi.avatarUrl
            val avatarKey = if (native) "u:${source.key}:${account!!.userId}:$aurl" else "u:${NeteaseApi.userId}"
            ui.drawCover(av, if (aurl.isNotEmpty()) CoverCache.get(avatarKey, aurl) else null, 14f)
            val who = if (native) account!!.nickname.ifEmpty { I18n.text(source.label) + " · " + account.userId }
                else NeteaseApi.nickname.ifEmpty { I18n.text("Logged in") }
            ui.text(ui.trim(who, textW, 12f), av.right + 10f, ub.y + 14f, 12f, Theme.TEXT_PRIMARY)
        } else {
            // 没登录：只有头像 + "登录"这一块是按钮，不是整行
            val label = ui.trim(I18n.text("Log in"), textW, 12f, Theme.FONT_SEMIBOLD)
            val lb = LayoutBounds(ub.x + 2f, ub.y + 3f, min(chip.x - 6f, av.right + 10f + ui.width(label, 12f, Theme.FONT_SEMIBOLD) + 10f) - (ub.x + 2f), 34f)
            if (ui.hover(lb)) ui.card(lb, Theme.CARD_HOVER, 10f)
            ui.ctx.drawShape(av, ShapeStyle(Theme.TRACK, 14f))
            ui.icon("?", av.x + 14f, av.y + 14f, 12f, Theme.TEXT_SECONDARY)
            ui.text(label, av.right + 10f, ub.y + 14f, 12f, RED, Theme.FONT_SEMIBOLD)
            ui.hits += lb to {
                menuOpen = false
                screen.login.open()
            }
        }
    }

    /** 菜单高度随已启用的 Salt 入口和当前账号操作调整。 */
    private fun menuTop(side: LayoutBounds): Float {
        val count = MusicSource.values().size + if (screen.saltAvailable) 1 else 0
        val canLogOut = PlaybackSessions.mode != PlaybackMode.SALT && loggedIn(MusicSources.selected)
        val h = 8f + count * MENU_ITEM_H + (if (canLogOut) 9f + MENU_ITEM_H else 0f) + 8f
        return side.bottom - 46f - 6f - h
    }

    /** 推进菜单的弹簧（按 1/240 秒的小步走，跳帧时也稳定） */
    private fun stepMenu(frameDt: Float) {
        var dt = frameDt
        val target = if (menuOpen) 1f else 0f
        // 弹出：稍软、带一点回弹，纵向比横向更软，像液体被拉出来；收起：更快，不回弹
        val kw = if (menuOpen) 320f else 560f
        val dw = if (menuOpen) 23f else 48f
        val kh = if (menuOpen) 210f else 560f
        val dh = if (menuOpen) 16f else 48f
        while (dt > 0f) {
            val step = kotlin.math.min(dt, 1f / 240f)
            vw += ((target - sw) * kw - vw * dw) * step
            sw += vw * step
            vh += ((target - sh) * kh - vh * dh) * step
            sh += vh * step
            dt -= step
        }
        if (!menuOpen && sw < 0.004f && sh < 0.004f) {
            sw = 0f; sh = 0f; vw = 0f; vh = 0f
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** 菜单当前的大小：从音源按钮（右下角对齐）长到完整菜单，冲过头时往左上多鼓出来一点 */
    private fun menuRect(side: LayoutBounds): LayoutBounds {
        val top = menuTop(side)
        val full = LayoutBounds(side.x + 6f, top, side.width - 12f, side.bottom - 46f - 6f - top)
        val c = chipRect
        val right = lerp(c.right, full.right, sw.coerceIn(0f, 1f))
        val bottom = lerp(c.bottom, full.bottom, sh.coerceIn(0f, 1f))
        val w = max(1f, lerp(c.width, full.width, sw))
        val h = max(1f, lerp(c.height, full.height, sh))
        return LayoutBounds(right - w, bottom - h, w, h)
    }

    /** 音源菜单：在账号栏上方弹出，三个音源（已登录的带小圆点）+ 退出当前账号 */
    private fun drawSourceMenu(side: LayoutBounds, menu: LayoutBounds) {
        val ctx = ui.ctx
        val current = MusicSources.selected
        val salt = PlaybackSessions.mode == PlaybackMode.SALT
        val sources = MusicSource.values()
        val itemH = MENU_ITEM_H
        val canLogOut = !salt && loggedIn(current)
        val full = LayoutBounds(side.x + 6f, menuTop(side), side.width - 12f, 0f)
        // 液态玻璃底：圆角、斜面和折射跟着当前大小走，小的时候也不会变形
        val r = kotlin.math.min(14f, menu.height / 2f)
        val edge = kotlin.math.min(12f, kotlin.math.min(menu.width, menu.height) / 3f)
        ctx.drawGlass(menu, MENU_GLASS.copy(radius = r, bevel = edge, refraction = edge))
        ctx.drawShape(menu, ShapeStyle(Color(0f, 0f, 0f, 0f), r, 1f, Color(1f, 1f, 1f, 0.10f)))

        // 内容在菜单长到一半以后逐行淡入、从下往上浮起；收起时一起淡出
        val content = ((sh - 0.35f) / 0.5f).coerceIn(0f, 1f)
        val interactive = menuOpen && sh > 0.85f
        if (interactive) menuRects += menu
        val hits = mutableListOf<Hit>()
        ui.beginClip(menu)
        var y = full.y + 8f
        var index = 0
        fun rowAt(rowY: Float): Pair<LayoutBounds, Float> {
            val a = (content * 1.4f - index * 0.12f).coerceIn(0f, 1f)
            index++
            return LayoutBounds(full.x + 6f, rowY + (1f - a) * 8f, full.width - 12f, itemH - 2f) to a
        }
        for (s in sources) {
            val (row, a) = rowAt(y)
            val on = !salt && s == current
            ctx.pushOpacity(a)
            if (on) ui.card(row, RED.copy(a = 0.20f), 8f) else if (interactive && ui.hover(row)) ui.card(row, Theme.CARD_HOVER, 8f)
            if (loggedIn(s)) ctx.drawShape(LayoutBounds(row.x + 10f, row.y + row.height / 2f - 3f, 6f, 6f), ShapeStyle(Theme.GREEN, 3f))
            ui.text(I18n.text(s.label), row.x + 24f, row.y + 9f, 12f, if (on) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY,
                if (on) Theme.FONT_SEMIBOLD else Theme.FONT_MEDIUM)
            ctx.popOpacity()
            if (interactive) {
                menuRects += row
                hits += row to {
                    menuOpen = false
                    screen.selectSource(s)
                }
            }
            y += itemH
        }
        if (screen.saltAvailable) {
            val (row, a) = rowAt(y)
            ctx.pushOpacity(a)
            if (salt) ui.card(row, RED.copy(a = 0.20f), 8f)
            else if (interactive && ui.hover(row)) ui.card(row, Theme.CARD_HOVER, 8f)
            ui.text(I18n.text("Salt Player"), row.x + 24f, row.y + 9f, 12f,
                if (salt) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY,
                if (salt) Theme.FONT_SEMIBOLD else Theme.FONT_MEDIUM)
            ctx.popOpacity()
            if (interactive) {
                menuRects += row
                hits += row to {
                    menuOpen = false
                    screen.selectSalt()
                }
            }
            y += itemH
        }
        if (canLogOut) {
            val (row0, a) = rowAt(y + 9f)
            ctx.pushOpacity(a)
            ctx.drawShape(LayoutBounds(full.x + 12f, row0.y - 5f, full.width - 24f, 1f), ShapeStyle(Color(1f, 1f, 1f, 0.08f), 0.5f))
            if (interactive && ui.hover(row0)) ui.card(row0, Theme.CARD_HOVER, 8f)
            ui.text(I18n.text("Log out"), row0.x + 24f, row0.y + 9f, 12f, Theme.TEXT_TERTIARY, Theme.FONT_MEDIUM)
            ctx.popOpacity()
            if (interactive) {
                menuRects += row0
                hits += row0 to {
                    menuOpen = false
                    try {
                        if (current == MusicSource.NETEASE) NeteaseApi.logout() else NativeAccounts.logout(current)
                        Library.refreshAll()
                    } catch (_: Exception) { Library.showStatus("Unable to clear the saved login. Please try again.") }
                }
            }
        }
        ui.endClip()
        // 菜单里的空白处也算菜单：点了不穿透到下面的歌单（菜单盖在歌单上面，所以它的点击排在最前面）
        if (interactive) hits += menu to {}
        ui.hits.addAll(0, hits)
    }

    /** 侧栏选中块（用上一帧记下的选中行位置） */
    private fun drawSelectionPill(side: LayoutBounds) {
        val now = System.currentTimeMillis()
        val tgt = selTarget
        if (tgt != null) {
            if (pillAlpha < 0.02f || pillKey.isEmpty()) {
                pillTop = tgt.y
                pillBot = tgt.bottom
                pillKey = selKey
                pillAt = 0L
            } else if (selKey != pillKey) {
                pillFromTop = pillTop
                pillFromBot = pillBot
                pillDown = tgt.y > pillTop
                pillAt = now
                pillKey = selKey
            }
            if (pillAt != 0L) {
                val e = (now - pillAt).toFloat()
                val pt = e / (if (pillDown) 380f else 220f)
                val pb = e / (if (pillDown) 220f else 380f)
                pillTop = pillFromTop + (tgt.y - pillFromTop) * ui.easeOut(pt)
                pillBot = pillFromBot + (tgt.bottom - pillFromBot) * ui.easeOut(pb)
                if (pt >= 1f && pb >= 1f) pillAt = 0L
            } else {
                pillTop = tgt.y
                pillBot = tgt.bottom
            }
            pillAlpha = min(1f, pillAlpha + ui.frameDt / 120f)
        } else {
            pillAlpha = max(0f, pillAlpha - ui.frameDt / 120f)
            if (pillAlpha <= 0f) pillKey = ""
        }
        if (pillAlpha > 0.01f && pillBot - pillTop > 2f) {
            ui.ctx.pushOpacity(pillAlpha)
            ui.ctx.drawGlass(LayoutBounds(side.x + 6f, pillTop, side.width - 12f, pillBot - pillTop), Theme.GLASS_SELECTION)
            ui.ctx.popOpacity()
        }
    }

    /** 搜索框有焦点时的按键；返回 true 表示按键被吃掉了 */
    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        if (menuOpen && keyCode == Keys.ESCAPE) {
            menuOpen = false
            return true
        }
        if (!searchFocused) return false
        when {
            keyCode == Keys.ESCAPE -> searchFocused = false
            keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> {
                searchPress.v -= 6f
                screen.doSearch()
            }
            keyCode == Keys.BACK -> if (searchText.isNotEmpty()) searchText = searchText.dropLast(1)
            DangoClient.platform.ctrlDown && keyCode == Keys.V -> {
                // 游戏里打不了中文时，可以复制后粘贴进来
                val clip = DangoClient.platform.clipboard.replace(Regex("[\\r\\n]"), " ")
                searchText = (searchText + clip).take(80)
            }
            DangoClient.platform.ctrlDown && keyCode == Keys.A -> searchText = ""
            Keys.isAllowedChar(typedChar) -> searchText = (searchText + typedChar).take(80)
        }
        return true
    }
}
