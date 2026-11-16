package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.AudioQuality
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.PlaybackQuality
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color
import kotlin.math.max

/**
 * 播放栏上的音质标签（"标准 / 极高 / 无损"，带边框的小字）：点开是"当前歌曲音质"面板，
 * 选一档就按那一档重新取这首歌，从现在的位置接着放。只管当前这首，换歌回到设置里的默认音质。
 */
internal class QualityPanel(private val ui: Ui) {

    var open = false
    /** 打开时调用（播放栏用它关掉别的面板） */
    var onOpen: (() -> Unit)? = null
    private val openAnim = Spring(320f, 26f)
    private val hoverA = Spring(300f, 26f)
    private val pressA = Spring(420f, 13f)

    var panelRect = LayoutBounds()
        private set
    private var buttonRect = LayoutBounds()

    /** 放别的播放器的歌、或者什么都没放时不显示 */
    val available: Boolean get() = MusicPlayer.current != null && MusicPlayer.active && !MusicPlayer.external

    /** 标签上写的：加载好了按实际拿到的显示（要的档拿不到会降），还在加载就显示要的那档 */
    private fun shownLevel(): AudioQuality = when (MusicPlayer.playingQuality) {
        "FLAC" -> AudioQuality.LOSSLESS
        "320K", "192K" -> AudioQuality.HIGH
        "" -> PlaybackQuality.wanted
        else -> AudioQuality.STANDARD
    }

    /** 标签宽度（播放栏排版要先知道） */
    fun buttonWidth(): Float = max(32f, ui.width(I18n.text(shownLevel().label), 11f, Theme.FONT_SEMIBOLD) + 16f)

    /** 和旁边的均衡器、调音按钮同一种样子：半透明圆角底，悬停变亮，点击回弹 */
    fun drawButton(x: Float, cy: Float) {
        val text = I18n.text(shownLevel().label)
        val b = LayoutBounds(x, cy - 14f, buttonWidth(), 28f)
        buttonRect = b
        val h = hoverA.tick(if (ui.hover(b) || open) 1f else 0f).coerceIn(0f, 1f)
        val k = 1f + pressA.tick(0f) * 0.14f
        ui.card(b.scaled(k), mixColor(Color(1f, 1f, 1f, 0.07f), Theme.CARD_HOVER, h), 9f * k)
        val size = 11f * k
        ui.text(text, b.x + b.width / 2f - ui.width(text, size, Theme.FONT_SEMIBOLD) / 2f, cy - size * 0.39f - 0.5f,
            size, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.hits += b to {
            pressA.v -= 7f
            open = !open
            if (open) onOpen?.invoke()
        }
    }

    fun drawPanel(bar: LayoutBounds) {
        if (!available) open = false
        val a = openAnim.tick(if (open) 1f else 0f).coerceIn(0f, 1f)
        if (a < 0.01f) {
            panelRect = LayoutBounds()
            return
        }
        val ctx = ui.ctx
        val rowH = 52f
        val levels = AudioQuality.values().reversed()   // 和网易云一样，高的在上面
        val w = 300f
        val h = 48f + levels.size * rowH + 10f
        val right = (buttonRect.x + buttonRect.width / 2f + w / 2f).coerceIn(bar.x + 6f + w, bar.right - 6f)
        val p = LayoutBounds(right - w, bar.y - 10f - h + (1f - a) * 10f, w, h)
        panelRect = p
        ctx.pushOpacity(a)
        ctx.drawGlass(p, QUALITY_GLASS)
        ui.text(I18n.text("Current song quality"), p.x + 18f, p.y + 18f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val selected = PlaybackQuality.wanted
        var y = p.y + 48f
        for (q in levels) {
            val r = LayoutBounds(p.x + 8f, y, w - 16f, rowH - 4f)
            if (ui.hover(r)) ui.card(r, Theme.CARD_HOVER, 10f)
            // 左边的圆形小图标：档位缩写
            val icon = LayoutBounds(r.x + 10f, r.y + r.height / 2f - 15f, 30f, 30f)
            ui.card(icon, Theme.CARD, 15f)
            val tag = when (q) { AudioQuality.LOSSLESS -> "SQ"; AudioQuality.HIGH -> "HQ"; AudioQuality.STANDARD -> I18n.text("Standard quality").take(1) }
            ui.text(tag, icon.x + icon.width / 2f - ui.width(tag, 9.5f, Theme.FONT_SEMIBOLD) / 2f, icon.y + 15f - 9.5f * 0.39f - 0.5f,
                9.5f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            val tx = icon.right + 12f
            val title = I18n.text(q.label) + when (q) { AudioQuality.LOSSLESS -> " (SQ)"; AudioQuality.HIGH -> " (HQ)"; else -> "" }
            ui.text(title, tx, r.y + 9f, 12.5f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            val sub = I18n.text(when (q) {
                AudioQuality.LOSSLESS -> "Lossless FLAC, needs membership and much more data"
                AudioQuality.HIGH -> "Near-CD detail, up to 320 kbps"
                AudioQuality.STANDARD -> "Up to 128 kbps"
            })
            ui.text(ui.trim(sub, r.right - tx - 34f, 9.5f, Theme.FONT_SEMIBOLD), tx, r.y + 28f, 9.5f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            // 选中的：右边红底白勾
            if (q == selected) {
                val ck = LayoutBounds(r.right - 30f, r.y + r.height / 2f - 10f, 20f, 20f)
                ctx.drawShape(ck, ShapeStyle(RED, 10f))
                ui.icon("✓", ck.x + 10f, ck.y + 10f, 11f, Theme.TEXT_PRIMARY)
            }
            ui.hits += r to {
                open = false
                if (q != selected) MusicPlayer.reloadWithQuality(q)
            }
            y += rowH
        }
        ui.hits += p to {}
        ctx.popOpacity()
    }

    /** 面板开着时点到外面就关掉（这一下不往下传）。返回 true = 处理掉了 */
    fun mouseDown(mx: Int, my: Int): Boolean {
        if (!open || panelRect.contains(mx, my) || buttonRect.contains(mx, my)) return false
        open = false
        return true
    }
}

private val QUALITY_GLASS = Theme.GLASS_SIDEBAR.copy(radius = 20f, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.22f), 20f, Vec2(0f, -4f)))
