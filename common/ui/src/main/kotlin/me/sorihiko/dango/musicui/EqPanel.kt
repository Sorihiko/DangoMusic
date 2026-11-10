package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.EqPresets
import me.sorihiko.dango.music.Equalizer
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.SoundTuning
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 播放栏上的"均衡器"：一个按钮，点开是浮在播放栏上面的面板。
 * 左边前级增益（自动，只显示）+ 10 段竖滑条，下面一排预设，最下面"按歌记住"（和变速变调共用）。
 * 均衡器开着并且有段不是 0 时按钮亮着。
 */
internal class EqPanel(private val ui: Ui) {

    var open = false
    /** 打开时调用（播放栏用它关掉另一个面板） */
    var onOpen: (() -> Unit)? = null
    private val openAnim = Spring(320f, 26f)
    private val hoverA = Spring(300f, 26f)
    private val pressA = Spring(420f, 13f)
    private val onA = Spring(240f, 24f)

    var panelRect = LayoutBounds()
        private set
    private var buttonRect = LayoutBounds()
    /** 每段滑条的竖槽（点击 / 拖动用） */
    private val bandRects = Array(Equalizer.BANDS) { LayoutBounds() }
    private var dragBand = -1

    private val key: String? get() = if (MusicPlayer.external) null else MusicPlayer.current?.key
    private val available: Boolean get() = !MusicPlayer.external

    fun drawButton(b: LayoutBounds, cy: Float) {
        buttonRect = b
        val enabled = available
        if (!enabled) open = false
        val s = SoundTuning.get(key)
        val o = onA.tick(if (enabled && s.eqActive) 1f else 0f).coerceIn(0f, 1f)
        val h = hoverA.tick(if (enabled && (ui.hover(b) || open)) 1f else 0f).coerceIn(0f, 1f)
        val k = 1f + pressA.tick(0f) * 0.14f
        val base = mixColor(Color(1f, 1f, 1f, 0.07f), Theme.CARD_HOVER, h)
        ui.card(b.scaled(k), mixColor(base, RED.copy(a = 0.85f), o), 9f * k)
        // 图标：五根高低不一的竖条（频谱）
        val c = if (enabled) Theme.TEXT_PRIMARY else Theme.ICON_DISABLED
        val cx = b.x + b.width / 2f
        for ((i, hh) in floatArrayOf(6f, 11f, 14f, 9f, 5f).withIndex()) {
            val x = cx + (i - 2) * 3.2f * k
            val bh = hh * k
            ui.card(LayoutBounds(x - 0.8f * k, cy - bh / 2f, 1.6f * k, bh), c, 0.8f * k)
        }
        if (enabled) ui.hits += b to {
            pressA.v -= 7f
            open = !open
            if (open) onOpen?.invoke()
        }
    }

    fun drawPanel(bar: LayoutBounds) {
        val a = openAnim.tick(if (open) 1f else 0f).coerceIn(0f, 1f)
        if (a < 0.01f) {
            panelRect = LayoutBounds()
            return
        }
        val ctx = ui.ctx
        val key = key
        val s = SoundTuning.get(key)
        val w = 440f
        val inner = w - 36f

        // 预设先排好行，才知道面板多高
        val chipH = 24f
        val chips = EqPresets.ALL.map { it to (ui.width(I18n.text(it.name), 11f, Theme.FONT_SEMIBOLD) + 22f) }
        val rows = ArrayList<MutableList<Pair<EqPresets.Preset, Float>>>()
        var rowW = Float.MAX_VALUE
        for (c in chips) {
            if (rowW + 6f + c.second > inner) { rows += mutableListOf<Pair<EqPresets.Preset, Float>>(); rowW = -6f }
            rows.last() += c
            rowW += 6f + c.second
        }
        val slidersH = 168f
        val h = 52f + slidersH + 24f + rows.size * (chipH + 6f) + 10f + 52f
        val right = min(bar.right - 6f, buttonRect.x + buttonRect.width / 2f + w / 2f)
        val p = LayoutBounds(max(bar.x + 6f, right - w), bar.y - 10f - h + (1f - a) * 10f, w, h)
        panelRect = p
        ctx.pushOpacity(a)
        ctx.drawGlass(p, EQ_GLASS)
        val x0 = p.x + 18f
        val x1 = p.right - 18f

        // 标题、重置、总开关
        ui.text(I18n.text("Equalizer"), x0, p.y + 18f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.toggleSwitch(LayoutBounds(x1 - 36f, p.y + 16f, 36f, 20f), s.eqOn, p, "eq.on") {
            SoundTuning.update(key, s.with(eqOn = !s.eqOn))
        }
        if (s.eq.any { abs(it) > 0.01f }) {
            val label = I18n.text("Reset")
            val rw = ui.width(label, 11f, Theme.FONT_SEMIBOLD) + 20f
            val rb = LayoutBounds(x1 - 36f - 10f - rw, p.y + 14f, rw, 24f)
            ui.card(rb, if (ui.hover(rb)) Theme.CARD_HOVER else Theme.CARD, 12f)
            ui.text(label, rb.x + 10f, rb.y + 12f - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            ui.hits += rb to { SoundTuning.update(key, s.with(eq = FloatArray(Equalizer.BANDS))) }
        }

        // 滑条：前级（自动，只显示）+ 刻度 + 10 段
        val top = p.y + 52f
        val trackTop = top + 18f
        val trackH = 112f
        val labelY = trackTop + trackH + 10f
        val preW = 40f
        val scaleW = 26f
        val colW = (inner - preW - scaleW) / Equalizer.BANDS
        val on = s.eqOn
        val pre = if (on) Equalizer.autoPreampDb(s.eq) else 0f
        val preCx = x0 + preW / 2f
        val autoLabel = "AUTO"
        val aw = ui.width(autoLabel, 9f, Theme.FONT_SEMIBOLD) + 10f
        ui.card(LayoutBounds(preCx - aw / 2f, top - 2f, aw, 14f), Theme.GREEN.copy(a = 0.18f), 4f)
        ui.text(autoLabel, preCx - aw / 2f + 5f, top + 5f - 9f * 0.39f - 0.5f, 9f, Theme.GREEN, Theme.FONT_SEMIBOLD)
        vSlider(preCx, trackTop, trackH, pre, false, false)
        centered(I18n.text("Preamp"), preCx, labelY, 9.5f, Theme.TEXT_TERTIARY)
        centered(fmtDb(pre), preCx, labelY + 13f, 9.5f, Theme.TEXT_SECONDARY)
        // 刻度：+12 / 0 / -12
        val sx = x0 + preW + scaleW / 2f
        centered("+12", sx, trackTop - 4f, 9f, Theme.TEXT_TERTIARY)
        centered("0", sx, trackTop + trackH / 2f - 4f, 9f, Theme.TEXT_TERTIARY)
        centered("-12", sx, trackTop + trackH - 4f, 9f, Theme.TEXT_TERTIARY)
        for (i in 0 until Equalizer.BANDS) {
            val cx = x0 + preW + scaleW + colW * (i + 0.5f)
            val g = s.eq[i]
            centered(fmtDb(g), cx, top, 9.5f, if (on) Theme.TEXT_SECONDARY else Theme.TEXT_TERTIARY)
            bandRects[i] = LayoutBounds(cx - colW / 2f, trackTop - 6f, colW, trackH + 12f)
            vSlider(cx, trackTop, trackH, g, on, dragBand == i || ui.hover(bandRects[i]))
            centered(Equalizer.LABELS[i], cx, labelY, 9.5f, Theme.TEXT_TERTIARY)
        }

        // 预设
        var y = top + slidersH + 4f
        ui.text(I18n.text("Presets"), x0, y, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        y += 20f
        val current = if (on) EqPresets.matching(s.eq) else null
        for (row in rows) {
            var x = x0
            for ((preset, cw) in row) {
                val cb = LayoutBounds(x, y, cw, chipH)
                val selected = preset === current
                val fill = when {
                    selected -> RED.copy(a = 0.85f)
                    ui.hover(cb) -> Theme.CARD_HOVER
                    else -> Theme.CARD
                }
                ui.card(cb, fill, 8f)
                ui.text(I18n.text(preset.name), x + 11f, y + chipH / 2f - 11f * 0.39f - 0.5f, 11f,
                    if (selected) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
                ui.hits += cb to { SoundTuning.update(key, s.with(eqOn = true, eq = preset.gains)) }
                x += cw + 6f
            }
            y += chipH + 6f
        }

        // 按歌记住（和变速变调共用一份）
        y += 8f
        val row = LayoutBounds(x0 - 6f, y, inner + 12f, 42f)
        ui.card(row, Theme.CARD, 12f)
        val canRemember = key != null
        val remembered = SoundTuning.isRemembered(key)
        ui.text(I18n.text("Remember for this song"), x0 + 4f, y + 9f, 12f,
            if (canRemember) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        ui.text(I18n.text("Use this EQ, speed and pitch next time this song plays"), x0 + 4f, y + 26f, 9.5f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        val sw = LayoutBounds(x1 - 36f, y + 11f, 36f, 20f)
        if (canRemember) ui.toggleSwitch(sw, remembered, p, "eq.remember") {
            SoundTuning.setRemembered(key!!, !remembered)
        } else ui.card(sw, Theme.SWITCH_OFF, 10f)

        ui.hits += p to {}
        ctx.popOpacity()
    }

    /** 竖滑条：中线是 0 dB，红色从中线画到当前值，圆钮在当前值 */
    private fun vSlider(cx: Float, top: Float, h: Float, db: Float, enabled: Boolean, hot: Boolean) {
        val track = LayoutBounds(cx - 2f, top, 4f, h)
        ui.card(track, Theme.TRACK, 2f)
        val f = ((Equalizer.MAX_GAIN - db.coerceIn(-Equalizer.MAX_GAIN, Equalizer.MAX_GAIN)) / (2f * Equalizer.MAX_GAIN))
        val ky = top + h * f
        val mid = top + h / 2f
        val color = if (enabled) RED else Theme.TEXT_TERTIARY
        if (abs(ky - mid) > 0.5f) ui.card(LayoutBounds(track.x, min(ky, mid), 4f, abs(ky - mid)), color, 2f)
        val ks = if (hot && enabled) 13f else 11f
        ui.ctx.drawShape(LayoutBounds(cx - ks / 2f, ky - ks / 2f, ks, ks),
            ShapeStyle(if (enabled) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, ks / 2f))
    }

    private fun centered(s: String, cx: Float, y: Float, size: Float, color: Color) {
        ui.text(s, cx - ui.width(s, size, Theme.FONT_SEMIBOLD) / 2f, y, size, color, Theme.FONT_SEMIBOLD)
    }

    private fun fmtDb(v: Float): String {
        if (abs(v) < 0.05f) return "0"
        val t = if (abs(v - Math.round(v)) < 0.05f) Math.round(v).toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
        return if (v > 0) "+$t" else t
    }

    // ---------------------------------------------------------------- 鼠标

    /** 点在某段滑条上开始拖（均衡器顺便打开）；面板开着时点到外面就关掉。返回 true = 处理掉了 */
    fun mouseDown(mx: Int, my: Int): Boolean {
        if (!open) return false
        if (panelRect.contains(mx, my)) {
            for (i in 0 until Equalizer.BANDS) if (bandRects[i].contains(mx, my)) {
                dragBand = i
                mouseDrag(my)
                return true
            }
            return false
        }
        if (buttonRect.contains(mx, my)) return false
        open = false
        return true
    }

    val dragging: Boolean get() = dragBand >= 0

    fun mouseDrag(my: Int) {
        val i = dragBand
        if (i < 0) return
        val r = bandRects[i]
        val trackTop = r.y + 6f
        val trackH = r.height - 12f
        val f = ((my - trackTop) / trackH).coerceIn(0f, 1f)
        val v = SoundTuning.clampGain(Equalizer.MAX_GAIN - f * 2f * Equalizer.MAX_GAIN)
        val key = key
        val s = SoundTuning.get(key)
        if (v != s.eq[i] || !s.eqOn) {
            val g = s.eq.copyOf()
            g[i] = v
            SoundTuning.update(key, s.with(eqOn = true, eq = g))
        }
    }

    fun mouseUp() {
        dragBand = -1
    }
}

private val EQ_GLASS = Theme.GLASS_SIDEBAR.copy(radius = 22f, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.22f), 20f, Vec2(0f, -4f)))
