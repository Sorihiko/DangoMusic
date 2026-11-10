package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.HudRenderEvent
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 歌曲卡片 HUD：内置播放器放歌时，在游戏画面里显示一张液态玻璃卡片
 * （封面 / 歌名 / 歌手 / 进度条）。打开聊天栏时可以用鼠标拖动，位置会保存。
 */
@ModuleMeta(Category.MUSIC, "SongCard")
class SongCardModule : Module() {

    /** 默认用灵动岛，歌曲卡片要自己打开 */
    override val enabledByDefault: Boolean get() = false

    @ModuleValue("Scale", "Card size")
    private val scale = FloatValue(0.6f, 2f, 1f, 1)

    @ModuleValue("Show when paused", "Keep the card visible while paused")
    private val showPaused = BooleanValue(false)

    @ModuleValue("Pos X", "Horizontal position (or drag it with chat open)")
    private val posX = FloatValue(0f, 1f, 0.01f, 3)

    @ModuleValue("Pos Y", "Vertical position (or drag it with chat open)")
    private val posY = FloatValue(0f, 1f, 0.05f, 3)

    private var context: GuiCanvas? = null

    private var dragging = false
    private var dragDX = 0f
    private var dragDY = 0f
    private var appear = 0f
    private var lastNanos = 0L

    /** 卡片正在显示（Lyrics 模块据此隐藏它自己的左上角信息） */
    val showing: Boolean get() = enabled && appear > 0.01f

    private val mt = MixedText()

    @EventHandler
    fun render(e: HudRenderEvent) {
        if (!enabled) return
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 16f else ((now - lastNanos) / 1e6f).coerceAtMost(50f)
        lastNanos = now

        val t = MusicPlayer.shownTrack
        val visible = t != null && MusicPlayer.shownActive &&
            (MusicPlayer.shownState != MusicPlayer.State.PAUSED || showPaused.get())
        appear += ((if (visible) 1f else 0f) - appear) * (1f - kotlin.math.exp(-dt / 120f))
        if (appear < 0.01f || t == null) return

        val platform = DangoClient.platform
        val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val lw = platform.windowWidth / uiScale
        val lh = platform.windowHeight / uiScale
        val mx = platform.mouseX / uiScale
        val my = platform.mouseY / uiScale

        val w = cardW * scale.value
        val h = cardH * scale.value
        var x = posX.value * (lw - w)
        var y = posY.value * (lh - h)

        // 打开聊天栏时可以拖动
        val chatOpen = platform.chatOpen
        val down = chatOpen && platform.isMouseDown(0)
        if (down) {
            if (!dragging && mx in x..(x + w) && my in y..(y + h)) {
                dragging = true
                dragDX = mx - x
                dragDY = my - y
            }
            if (dragging) {
                x = (mx - dragDX).coerceIn(0f, lw - w)
                y = (my - dragDY).coerceIn(0f, lh - h)
                posX.setValue(if (lw > w) x / (lw - w) else 0f)
                posY.setValue(if (lh > h) y / (lh - h) else 0f)
            }
        } else {
            dragging = false
        }

        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(true, mx.toInt(), my.toInt())
        try {
            ctx.pushScale(uiScale, uiScale, 1f)
            ctx.pushOpacity(appear)
            mt.clear()
            drawCard(ctx, LayoutBounds(x, y + (1f - appear) * 10f, w, h), chatOpen)
            ctx.popOpacity()
            ctx.popTransform()
            mt.flush(uiScale)
        } finally {
            ctx.end()
        }
    }

    /** 卡片尺寸（逻辑像素，未乘 scale） */
    private val cardW = 320f
    private val cardH = 104f

    /** 卡片用的玻璃：和窗口同材质，但阴影小很多（HUD 上大阴影会显得脏） */
    private fun cardGlass(k: Float) = Theme.GLASS_WINDOW.copy(
        radius = 24f * k,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.14f), 10f * k, Vec2(0f, -3f * k)),
    )

    /**
     * 排版参考 iOS 锁屏的播放卡片（不要下面那排按钮）：
     *   封面 | 歌名 / 歌手            ▂▅▇▅▂
     *   0:14 ━━━━━━━━━━━━━━━━━━ -6:18
     */
    private fun drawCard(ctx: GuiCanvas, b: LayoutBounds, chatOpen: Boolean) {
        val t = MusicPlayer.shownTrack ?: return
        val k = scale.value
        ctx.drawGlass(b, cardGlass(k))
        if (chatOpen) ctx.drawShape(b, ShapeStyle(Color(0f, 0f, 0f, 0f), 24f * k, 1f, Theme.ACCENT))

        val pad = 14f * k
        val cs = 50f * k
        val cover = LayoutBounds(b.x + pad, b.y + pad, cs, cs)
        val loc = CoverCache.trackRounded(t) ?: CoverCache.track(t)
        CoverCache.upload()
        if (loc != null) ctx.drawImage(cover, loc) else ctx.drawShape(cover, ShapeStyle(Theme.TRACK, 9f * k))

        // 右上角的声波（播放中跳动，暂停时变平）
        val playing = MusicPlayer.shownState == MusicPlayer.State.PLAYING
        val now = System.nanoTime() / 1e9
        val bars = 5
        val bw = 2.6f * k
        val gap = 2.4f * k
        val waveRight = b.right - pad
        val waveCy = cover.y + 12f * k
        for (i in 0 until bars) {
            val base = floatArrayOf(0.45f, 0.75f, 1f, 0.7f, 0.5f)[i]
            val hh = if (playing)
                (4f + 11f * base * (0.45f + 0.55f * (0.5f + 0.5f * sin(now * (5.0 + i * 1.3) + i * 1.9).toFloat()))) * k
            else 3f * k
            ctx.drawShape(
                LayoutBounds(waveRight - (bars - i) * (bw + gap) + gap, waveCy - hh / 2f, bw, hh),
                ShapeStyle(Theme.TEXT_SECONDARY, bw / 2f),
            )
        }

        // 歌名 / 歌手
        val tx = cover.right + 12f * k
        val tw = waveRight - bars * (bw + gap) - 10f * k - tx
        text(ctx, trim(t.name, tw, 15f * k, Theme.FONT_SEMIBOLD), tx, cover.y + 8f * k, 15f * k, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        text(ctx, trim(t.artists.joinToString(" / "), waveRight - tx, 13f * k), tx, cover.y + 28f * k, 13f * k, Theme.TEXT_SECONDARY)

        // 进度：左边已播放，右边剩余（负号），中间粗进度条
        val dur = MusicPlayer.shownDurationMs
        val pos = MusicPlayer.shownPositionMs.coerceIn(0L, max(0L, dur))
        val ts = 10.5f * k
        val rowCy = b.bottom - pad - 5f * k
        val left = fmt(pos)
        val right = "-" + fmt(max(0L, dur - pos))
        val lw = width(left, ts, Theme.FONT_MEDIUM)
        val rw = width(right, ts, Theme.FONT_MEDIUM)
        val labelW = max(max(lw, rw), width("0:00", ts, Theme.FONT_MEDIUM))
        val textY = rowCy - ts * 0.39f - 0.5f * k
        text(ctx, left, b.x + pad, textY, ts, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
        text(ctx, right, b.right - pad - rw, textY, ts, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
        val barH = 5f * k
        val barX = b.x + pad + labelW + 9f * k
        val bar = LayoutBounds(barX, rowCy - barH / 2f, b.right - pad - labelW - 9f * k - barX, barH)
        ctx.drawShape(bar, ShapeStyle(Color(1f, 1f, 1f, 0.18f), barH / 2f))
        if (dur > 0) {
            val f = (pos.toDouble() / dur).coerceIn(0.0, 1.0).toFloat()
            ctx.drawShape(LayoutBounds(bar.x, bar.y, max(barH, bar.width * f), bar.height), ShapeStyle(Color(1f, 1f, 1f, 0.92f), barH / 2f))
        }
    }

    // ---------------------------------------------------------------- 文字（拉丁用菜单字体，中文用 MC 字体）

    private fun fmt(ms: Long): String {
        val s = max(0L, ms / 1000)
        return "${s / 60}:${"%02d".format(s % 60)}"
    }

    private fun text(ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) =
        mt.text(ctx, s, x, y, size, color, font)

    private fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    private fun trim(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): String = mt.trim(s, maxW, size, font)
}
