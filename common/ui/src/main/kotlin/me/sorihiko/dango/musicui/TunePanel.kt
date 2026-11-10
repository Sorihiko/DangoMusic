package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n
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
import kotlin.math.roundToInt

/**
 * 播放栏上的"变速变调"：一个按钮，点开是浮在播放栏上面的小面板（速度、音调、音调跟着速度、按歌记住）。
 * 不是 1x / 0 半音时按钮亮着，免得忘了开着。
 */
internal class TunePanel(private val ui: Ui) {

    var open = false
    /** 打开时调用（播放栏用它关掉另一个面板） */
    var onOpen: (() -> Unit)? = null
    private val openAnim = Spring(320f, 26f)
    private val hoverA = Spring(300f, 26f)
    private val pressA = Spring(420f, 13f)
    private val onA = Spring(240f, 24f)

    /** 面板这一帧的位置（没打开是空的）：画内容时鼠标在这里不算悬停在下面 */
    var panelRect = LayoutBounds()
        private set
    private var buttonRect = LayoutBounds()
    private var speedRect = LayoutBounds()
    private var pitchRect = LayoutBounds()

    private enum class Drag { NONE, SPEED, PITCH }
    private var drag = Drag.NONE

    /** 当前这首（Dango 自己放的）；显示别的播放器时不能调 */
    private val key: String? get() = if (MusicPlayer.external) null else MusicPlayer.current?.key
    private val available: Boolean get() = !MusicPlayer.external

    fun drawButton(b: LayoutBounds, cy: Float) {
        buttonRect = b
        val enabled = available
        if (!enabled) open = false
        val s = SoundTuning.get(key)
        val o = onA.tick(if (enabled && !s.isDefault) 1f else 0f).coerceIn(0f, 1f)
        val h = hoverA.tick(if (enabled && (ui.hover(b) || open)) 1f else 0f).coerceIn(0f, 1f)
        val k = 1f + pressA.tick(0f) * 0.14f
        val base = mixColor(Color(1f, 1f, 1f, 0.07f), Theme.CARD_HOVER, h)
        ui.card(b.scaled(k), mixColor(base, RED.copy(a = 0.85f), o), 9f * k)
        // 图标：三道横线，上面的圆钮错开（像调音台的推子）
        val c = if (enabled) Theme.TEXT_PRIMARY else Theme.ICON_DISABLED
        val cx = b.x + b.width / 2f
        val lw = 14f * k
        for ((i, f) in floatArrayOf(0.30f, 0.70f, 0.45f).withIndex()) {
            val y = cy + (i - 1) * 4.5f * k
            ui.card(LayoutBounds(cx - lw / 2f, y - 0.7f, lw, 1.4f), c, 0.7f)
            val kx = cx - lw / 2f + lw * f
            ui.ctx.drawShape(LayoutBounds(kx - 2f * k, y - 2f * k, 4f * k, 4f * k), ShapeStyle(c, 2f * k))
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
        val locked = SoundTuning.speedLocked
        val w = 268f
        val h = 276f
        val right = min(bar.right - 6f, buttonRect.x + buttonRect.width / 2f + w / 2f)
        val p = LayoutBounds(right - w, bar.y - 10f - h + (1f - a) * 10f, w, h)
        panelRect = p
        ctx.pushOpacity(a)
        ctx.drawGlass(p, PANEL_GLASS)
        val x0 = p.x + 18f
        val x1 = p.right - 18f

        // 标题 + 恢复默认
        ui.text(I18n.text("Sound tuning"), x0, p.y + 16f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.text(I18n.text("Speed · Pitch"), x0, p.y + 35f, 10f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        if (!s.isDefault) {
            val label = I18n.text("Reset")
            val rw = ui.width(label, 11f, Theme.FONT_SEMIBOLD) + 20f
            val rb = LayoutBounds(x1 - rw, p.y + 16f, rw, 24f)
            ui.card(rb, if (ui.hover(rb)) Theme.CARD_HOVER else Theme.CARD, 12f)
            ui.text(label, rb.x + 10f, rb.y + 12f - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            ui.hits += rb to { SoundTuning.update(key, s.with(speed = 1f, semitones = 0f)) }
        }

        // 速度
        var y = p.y + 64f
        ui.text(I18n.text("Speed"), x0, y, 11.5f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        val speedLabel = if (locked) I18n.text("Fixed at 1x in Listen Together") else fmtSpeed(s.speed)
        ui.text(speedLabel, x1 - ui.width(speedLabel, 11.5f, Theme.FONT_SEMIBOLD), y, 11.5f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        speedRect = LayoutBounds(x0, y + 26f, x1 - x0, 4f)
        val speedF = if (locked) frac(1f) else frac(s.speed)
        slider(speedRect, speedF, frac(1f), !locked, drag == Drag.SPEED)

        // 音调
        y += 54f
        ui.text(I18n.text("Pitch"), x0, y, 11.5f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        val semis = if (s.linked) SoundTuning.linkedSemitones(if (locked) 1f else s.speed) else s.semitones
        val pitchLabel = fmtSemitones(semis, s.linked) + if (s.linked) " · " + I18n.text("auto") else ""
        ui.text(pitchLabel, x1 - ui.width(pitchLabel, 11.5f, Theme.FONT_SEMIBOLD), y, 11.5f,
            if (s.linked) Theme.TEXT_SECONDARY else Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        pitchRect = LayoutBounds(x0, y + 26f, x1 - x0, 4f)
        slider(pitchRect, ((semis + SoundTuning.MAX_SEMITONES) / (2f * SoundTuning.MAX_SEMITONES)).coerceIn(0f, 1f), 0.5f, !s.linked, drag == Drag.PITCH)

        // 音调跟着速度
        y += 56f
        ui.text(I18n.text("Pitch follows speed"), x0, y + 10f - 12f * 0.39f - 0.5f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.toggleSwitch(LayoutBounds(x1 - 36f, y, 36f, 20f), s.linked, p, "tune.linked") {
            SoundTuning.update(key, s.with(linked = !s.linked))
        }

        // 按歌记住
        y += 34f
        val canRemember = key != null
        val remembered = SoundTuning.isRemembered(key)
        ui.text(I18n.text("Remember for this song"), x0, y + 10f - 12f * 0.39f - 0.5f, 12f,
            if (canRemember) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        ui.text(ui.trim(I18n.text("Use this EQ, speed and pitch next time this song plays"), x1 - x0 - 46f, 9.5f, Theme.FONT_SEMIBOLD),
            x0, y + 23f, 9.5f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        if (canRemember) ui.toggleSwitch(LayoutBounds(x1 - 36f, y, 36f, 20f), remembered, p, "tune.remember") {
            SoundTuning.setRemembered(key!!, !remembered)
        } else ui.card(LayoutBounds(x1 - 36f, y, 36f, 20f), Theme.SWITCH_OFF, 10f)

        // 面板空白处挡住点击，不漏到下面的列表
        ui.hits += p to {}
        ctx.popOpacity()
    }

    /** 滑条：底槽 + 从默认值到当前值的红色一段 + 默认值的小刻度 + 圆钮 */
    private fun slider(r: LayoutBounds, f: Float, zero: Float, enabled: Boolean, dragging: Boolean) {
        val ctx = ui.ctx
        ui.card(r, Theme.TRACK, 2f)
        val color = if (enabled) RED else Theme.TEXT_TERTIARY
        val a = r.x + r.width * min(f, zero)
        val b = r.x + r.width * max(f, zero)
        if (b - a > 0.5f) ui.card(LayoutBounds(a, r.y, b - a, r.height), color, 2f)
        ui.card(LayoutBounds(r.x + r.width * zero - 0.75f, r.y + r.height + 4f, 1.5f, 5f), Theme.TEXT_TERTIARY, 0.75f)
        val kx = r.x + r.width * f
        val ks = if (dragging) 14f else if (enabled && ui.hover(r.expand(4f, 8f))) 12f else 10f
        ctx.drawShape(LayoutBounds(kx - ks / 2f, r.y + r.height / 2f - ks / 2f, ks, ks),
            ShapeStyle(if (enabled) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, ks / 2f))
    }

    private fun frac(speed: Float) = (speed - SoundTuning.MIN_SPEED) / (SoundTuning.MAX_SPEED - SoundTuning.MIN_SPEED)

    private fun fmtSpeed(v: Float): String {
        val r = (v * 100f).roundToInt()
        return if (r % 100 == 0) "${r / 100}x" else if (r % 10 == 0) String.format(java.util.Locale.ROOT, "%.1fx", v) else String.format(java.util.Locale.ROOT, "%.2fx", v)
    }

    private fun fmtSemitones(v: Float, fractional: Boolean): String {
        if (abs(v) < 0.05f) return "0"
        val t = if (fractional) String.format(java.util.Locale.ROOT, "%.1f", v) else v.roundToInt().toString()
        return if (v > 0) "+$t" else t
    }

    // ---------------------------------------------------------------- 鼠标

    /** 点在滑条上开始拖；面板开着时点到外面就关掉（这一下不往下传）。返回 true = 处理掉了 */
    fun mouseDown(mx: Int, my: Int): Boolean {
        if (!open) return false
        if (panelRect.contains(mx, my)) {
            val s = SoundTuning.get(key)
            if (!SoundTuning.speedLocked && speedRect.expand(6f, 9f).contains(mx, my)) {
                drag = Drag.SPEED
                mouseDrag(mx)
                return true
            }
            if (!s.linked && pitchRect.expand(6f, 9f).contains(mx, my)) {
                drag = Drag.PITCH
                mouseDrag(mx)
                return true
            }
            return false
        }
        if (buttonRect.contains(mx, my)) return false
        open = false
        return true
    }

    val dragging: Boolean get() = drag != Drag.NONE

    fun mouseDrag(mx: Int) {
        val key = key
        val s = SoundTuning.get(key)
        when (drag) {
            Drag.SPEED -> {
                val f = ((mx - speedRect.x) / speedRect.width).coerceIn(0f, 1f)
                val v = SoundTuning.clampSpeed(SoundTuning.MIN_SPEED + f * (SoundTuning.MAX_SPEED - SoundTuning.MIN_SPEED))
                if (v != s.speed) SoundTuning.update(key, s.with(speed = v))
            }
            Drag.PITCH -> {
                val f = ((mx - pitchRect.x) / pitchRect.width).coerceIn(0f, 1f)
                val v = SoundTuning.clampSemitones(-SoundTuning.MAX_SEMITONES + f * 2f * SoundTuning.MAX_SEMITONES)
                if (v != s.semitones) SoundTuning.update(key, s.with(semitones = v))
            }
            Drag.NONE -> {}
        }
    }

    fun mouseUp() {
        drag = Drag.NONE
    }
}

private val PANEL_GLASS = Theme.GLASS_SIDEBAR.copy(radius = 22f, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.22f), 20f, Vec2(0f, -4f)))
