package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicSources
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color
import kotlin.math.max
import kotlin.math.min

/**
 * 播放栏红心旁边的"+"：点开列出当前音源里自己建的歌单，点一个就把正在放的歌加进去。
 * 网易云、QQ 音乐、酷狗都支持（要登录）；放别的播放器的歌时不显示。
 */
internal class AddPanel(private val ui: Ui) {

    var open = false
    /** 打开时调用（播放栏用它关掉别的面板） */
    var onOpen: (() -> Unit)? = null
    private val openAnim = Spring(320f, 26f)
    private val hoverA = Spring(300f, 26f)
    private val pressA = Spring(420f, 13f)

    var panelRect = LayoutBounds()
        private set
    private var listRect = LayoutBounds()
    private var buttonRect = LayoutBounds()
    /** 打开面板时的那首歌（面板开着时换歌了也加这首） */
    private var target: NeteaseTrack? = null
    private var scroll = 0

    /** 现在这首能不能加到歌单 */
    fun available(t: NeteaseTrack?): Boolean =
        t != null && !MusicPlayer.external && MusicSources.api(t.source).canAddToPlaylist

    fun drawButton(b: LayoutBounds, t: NeteaseTrack) {
        buttonRect = b
        val h = hoverA.tick(if (ui.hover(b) || open) 1f else 0f).coerceIn(0f, 1f)
        val k = 1f + pressA.tick(0f) * 0.14f + h * 0.04f
        if (h > 0.01f) {
            val d = b.scaled(k)
            ui.ctx.drawShape(d, ShapeStyle(Theme.CARD_HOVER.copy(a = Theme.CARD_HOVER.a * h), d.height / 2f))
        }
        // 加号：两道细条
        val cx = b.x + b.width / 2f
        val cy = b.y + b.height / 2f
        val c = Theme.TEXT_SECONDARY
        ui.card(LayoutBounds(cx - 6f * k, cy - 0.8f * k, 12f * k, 1.6f * k), c, 0.8f * k)
        ui.card(LayoutBounds(cx - 0.8f * k, cy - 6f * k, 1.6f * k, 12f * k), c, 0.8f * k)
        ui.hits += b to {
            pressA.v -= 7f
            open = !open
            if (open) {
                target = t
                scroll = 0
                Library.loadAddTargets(t.source)
                onOpen?.invoke()
            }
        }
    }

    fun drawPanel(bar: LayoutBounds) {
        val a = openAnim.tick(if (open) 1f else 0f).coerceIn(0f, 1f)
        if (a < 0.01f) {
            panelRect = LayoutBounds()
            return
        }
        val ctx = ui.ctx
        val track = target
        val lists = if (Library.addTargetsSource == track?.source) Library.addTargets else null
        val rowH = 30f
        val shown = min(MAX_ROWS, max(1, lists?.size ?: 1))
        val w = 260f
        val h = 58f + shown * rowH + 10f
        val left = (buttonRect.x + buttonRect.width / 2f - w / 2f).coerceIn(bar.x + 6f, bar.right - 6f - w)
        val p = LayoutBounds(left, bar.y - 10f - h + (1f - a) * 10f, w, h)
        panelRect = p
        ctx.pushOpacity(a)
        ctx.drawGlass(p, ADD_GLASS)
        val x0 = p.x + 16f
        val x1 = p.right - 16f
        ui.text(I18n.text("Add to playlist"), x0, p.y + 14f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        track?.let { ui.text(ui.trim(it.name, x1 - x0, 10f, Theme.FONT_SEMIBOLD), x0, p.y + 32f, 10f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD) }

        listRect = LayoutBounds(p.x + 6f, p.y + 52f, w - 12f, shown * rowH)
        when {
            lists == null -> ui.text(I18n.text("Loading playlists…"), x0, listRect.y + 9f, 11f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            lists.isEmpty() -> ui.text(I18n.text("No playlists of your own yet"), x0, listRect.y + 9f, 11f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            else -> {
                scroll = scroll.coerceIn(0, max(0, lists.size - MAX_ROWS))
                val busy = Library.adding
                for (i in 0 until shown) {
                    val pl = lists.getOrNull(scroll + i) ?: break
                    val r = LayoutBounds(listRect.x, listRect.y + i * rowH, listRect.width, rowH - 2f)
                    if (!busy && ui.hover(r)) ui.card(r, Theme.CARD_HOVER, 8f)
                    val count = pl.trackCount.toString()
                    val cw = ui.width(count, 10f, Theme.FONT_SEMIBOLD)
                    ui.text(ui.trim(pl.name, r.width - 24f - cw, 12f, Theme.FONT_SEMIBOLD), r.x + 10f, r.y + r.height / 2f - 12f * 0.39f - 0.5f, 12f,
                        if (busy) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                    ui.text(count, r.right - 10f - cw, r.y + r.height / 2f - 10f * 0.39f - 0.5f, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
                    if (!busy && track != null) ui.hits += r to {
                        Library.addToPlaylist(pl, track)
                        open = false
                    }
                }
                // 还有更多：右边一条细滚动条
                if (lists.size > MAX_ROWS) {
                    val trackH = listRect.height
                    val thumbH = max(16f, trackH * MAX_ROWS / lists.size)
                    val y = listRect.y + (trackH - thumbH) * scroll / (lists.size - MAX_ROWS).toFloat()
                    ui.card(LayoutBounds(p.right - 5f, y, 2.5f, thumbH), Theme.TEXT_TERTIARY, 1.25f)
                }
            }
        }
        ui.hits += p to {}
        ctx.popOpacity()
    }

    /** 滚轮翻歌单（[wheel] 正数向上） */
    fun scroll(wheel: Int) {
        scroll += if (wheel > 0) -1 else 1
    }

    /** 面板开着时点到外面就关掉（这一下不往下传）。返回 true = 处理掉了 */
    fun mouseDown(mx: Int, my: Int): Boolean {
        if (!open || panelRect.contains(mx, my) || buttonRect.contains(mx, my)) return false
        open = false
        return true
    }

    private companion object {
        const val MAX_ROWS = 8
    }
}

private val ADD_GLASS = Theme.GLASS_SIDEBAR.copy(radius = 20f, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.22f), 20f, Vec2(0f, -4f)))
