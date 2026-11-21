package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.gfx.CjkStyle
import me.sorihiko.dango.gfx.GlassTextPass
import me.sorihiko.dango.gfx.GlassTextStyle
import me.sorihiko.dango.gfx.GlassTextView
import me.sorihiko.dango.gfx.TextCanvas
import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.ui.LayoutBounds
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.tan
import kotlin.math.tanh

/**
 * 驾驶舱歌词：歌词不浮在世界里，而是像座舱 / 头盔显示器一样贴在屏幕一侧。
 * 没有背景板，字自己发光（也可以是透明的玻璃字），默认是封面主色（跟着每首歌变），
 * 也可以选自定义颜色、封面渐变、彩虹、封面颜色流动的极光；踩到节拍时亮一下。
 * 整列带一点透视倾斜（像座舱侧边的屏，朝屏幕中间转），字略微斜体。
 * 歌词一行行向上滚，当前行放大、逐字点亮；可选惯性：转视角、跳跃时列表稍微滞后再弹回来。
 *
 * 只负责画：歌词、当前行、进度和设置都由 [LyricsModule] 每帧传进来。玻璃效果见 [GlassTextPass]。
 */
internal class SideLyrics {
    class Settings(
        /** true = 屏幕右侧，false = 左侧 */
        val right: Boolean,
        /** 字是玻璃；false = 实心的发光字 */
        val glass: Boolean,
        /** 转视角、跳跃时整列稍微滞后再弹回来 */
        val inertia: Boolean,
        /** 每句下面显示翻译（跟 3D 歌词的翻译开关走） */
        val translation: Boolean,
        /** 配色：[COCKPIT_COVER] / [COCKPIT_CUSTOM] / [COCKPIT_GRADIENT] / [COCKPIT_RAINBOW] / [COCKPIT_AURORA] */
        val colors: Int,
        /** 选"自定义颜色"时用的颜色（0xRRGGBB） */
        val color: Int,
        /** 字本身染上多少颜色（0..1）；0 = 白字配彩色光晕 */
        val tint: Float,
    )

    class Frame(
        val key: String,
        val title: String,
        val artist: String,
        val lines: List<LyricLine>,
        /** 当前行；-1 = 第一句之前（显示歌名那一行） */
        val index: Int,
        val positionMs: Long,
        /** 封面主色（0xRRGGBB；封面还没出来时传默认色） */
        val accent: Int,
        /** 封面的几种代表色（封面渐变、极光用；没有时传空） */
        val palette: List<Int>,
        /** 0..1，踩点时变大 */
        val beat: Float,
        /** 现在该不该显示（暂停也显示；聊天栏打开时淡出） */
        val visible: Boolean,
    )

    private var lastNanos = 0L

    /** 完全淡出了（关掉驾驶舱歌词后就不必每帧准备歌词数据） */
    val idle: Boolean get() = appear < 0.01f
    private var appear = 0f
    private var last: Frame? = null
    private var lastKey: String? = null
    private var scroll = Float.NaN
    private var scrollV = 0f
    /** 封面主色（换歌时慢慢过渡过去） */
    private var glowR = 0.95f
    private var glowG = 0.65f
    private var glowB = 0.76f

    /** 截断结果缓存（文字、字号、宽度都不变时不用每帧重新量） */
    private val trimCache = HashMap<String, String>()

    // ---- 惯性（见 updateSway）
    private var swayYaw = Float.NaN
    private var swayPitch = 0f
    private var swayCamY = 0.0
    private var turnYaw = 0f
    private var turnPitch = 0f
    private var rise = 0f
    private var swayX = 0f
    private var swayY = 0f
    private var swayVX = 0f
    private var swayVY = 0f

    /** 这一帧画字用的东西（draw 里设好，各个画行的函数共用） */
    private lateinit var tc: TextCanvas
    private var colorMode = COCKPIT_COVER
    private var customR = 1f
    private var customG = 1f
    private var customB = 1f
    private var palette: List<Int> = emptyList()
    private var timeSec = 0f
    private var italicTan = 0f
    private var alignRight = false
    private val rgb = FloatArray(3)

    fun draw(frame: Frame?, s: Settings) {
        val platform = DangoClient.platform
        if (platform.windowWidth <= 0 || platform.windowHeight <= 0) return
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        // 隔了一阵子才又画（列表隐藏过、游戏卡了）：惯性从头算，不把这段时间里转过的角度当成一下甩过去
        val gap = lastNanos == 0L || now - lastNanos > 250_000_000L
        lastNanos = now
        timeSec = ((now / 1_000_000L) % 3_600_000L) / 1000f

        // 出现 / 消失 0.2 秒渐变；消失时用最后一帧淡出
        if (frame != null) last = frame
        val f = last ?: return
        val want = if (frame != null && frame.visible) 1f else 0f
        appear += (want - appear) * (1f - exp(-dt / 0.18f))
        if (appear < 0.01f) {
            if (frame == null) last = null
            swayYaw = Float.NaN
            return
        }

        // 行：第 0 行是歌名，后面是歌词
        val rows = f.lines.size
        val target = (f.index + 1).coerceIn(0, rows).toFloat()
        if (scroll.isNaN() || f.key != lastKey || abs(target - scroll) > 4f) {
            // 换歌、拖进度：直接到位，不从头滚过来
            scroll = target
            scrollV = 0f
            if (f.key != lastKey) trimCache.clear()
            lastKey = f.key
        } else {
            // 略带回弹的弹簧，换行时整列平滑上移
            scrollV += ((target - scroll) * 150f - scrollV * 22f) * dt
            scroll += scrollV * dt
        }

        // 封面主色慢慢过渡（隐藏过一阵子再出现时直接用现在的颜色）；封面很暗时提亮，光才看得出来
        normalized((f.accent shr 16 and 0xFF) / 255f, (f.accent shr 8 and 0xFF) / 255f, (f.accent and 0xFF) / 255f, rgb)
        val kc = if (gap) 1f else 1f - exp(-dt / 0.4f)
        glowR += (rgb[0] - glowR) * kc
        glowG += (rgb[1] - glowG) * kc
        glowB += (rgb[2] - glowB) * kc
        colorMode = s.colors
        normalized((s.color shr 16 and 0xFF) / 255f, (s.color shr 8 and 0xFF) / 255f, (s.color and 0xFF) / 255f, rgb)
        customR = rgb[0]; customG = rgb[1]; customB = rgb[2]
        palette = f.palette

        // ---------------------------------------------------------------- 布局（逻辑像素，按窗口缩放）
        val k = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val lw = platform.windowWidth / k
        val lh = platform.windowHeight / k
        val rowH = if (s.translation) ROW_H_TRANSLATED else ROW_H
        alignRight = s.right
        val w = WIDTH.coerceAtMost(lw * 0.45f)
        val h = (VISIBLE_ROWS * rowH).coerceAtMost(lh - 16f)
        val margin = 36f
        val x = if (alignRight) lw - w - margin else margin
        val y = ((lh - h) / 2f).coerceAtLeast(8f)
        // 惯性偏移加在排好的位置之后：只是暂时晃一下，最多二十几像素，边距（36）够用
        updateSway(dt, if (s.inertia) INERTIA_STRENGTH else 0f, gap)
        val area = LayoutBounds(x + swayX, y + swayY, w, h)
        val center = area.y + h / 2f

        // 透视的支点在外侧边上：外侧不动，往屏幕中间的那一侧退远（不会被推出屏幕）
        val view = GlassTextView(
            pivotX = (if (alignRight) area.right else area.x) * k,
            pivotY = center * k,
            yaw = if (alignRight) -TILT else TILT,
            pitch = 0f,
        )
        // begin 成功之后一定要走到 end，所以能出错的准备工作都放在 begin 前面
        tc = DangoClient.gfx.text
        italicTan = tan(Math.toRadians(ITALIC.toDouble())).toFloat()
        val pulse = 0.8f + 0.25f * f.beat.coerceIn(0f, 1f)
        val style = GlassTextStyle(
            opacity = if (s.glass) 0.85f else 1f,
            refraction = 8f * REFRACTION * k,
            emission = (0.25f + GLOW_STRENGTH) * pulse,
            halo = (0.2f + GLOW_STRENGTH) * pulse,
            frost = 0.12f,
            solid = !s.glass,
            tintText = s.tint.coerceIn(0f, 1f),
            brightness = 1f,
        )
        val pass = DangoClient.gfx.glassText
        if (!pass.begin(view)) return
        try {
            tc.push()
            try {
                tc.scale(k, k)
                drawGuide(pass, f, area, center, rowH)
                drawRows(f, s, area, center, rowH)
            } finally {
                tc.pop()
            }
        } finally {
            pass.end(style)
        }
    }

    private fun drawRows(f: Frame, s: Settings, area: LayoutBounds, center: Float, rowH: Float) {
        val currentSize = 21f
        val otherSize = 15f
        val translationSize = 11f
        val rows = f.lines.size
        val half = VISIBLE_ROWS / 2f
        val first = floor(scroll - half - 1f).toInt().coerceAtLeast(0)
        val lastRow = ceil(scroll + half + 1f).toInt().coerceAtMost(rows)
        val other = OTHER_LINES
        val otherLit = 0.25f + 0.5f * other
        for (r in first..lastRow) {
            val d = r - scroll
            val dist = abs(d)
            // 越靠边越淡，最外一行几乎透明：没有硬边
            val edge = ((half + 0.35f - dist) / 1.6f).coerceIn(0f, 1f)
            val fade = edge * edge * (3f - 2f * edge)
            if (fade <= 0.01f) continue
            // 0..1：离中间越近越接近"当前行"的样子（滚动时连续变化）
            val emph = (1f - dist).coerceIn(0f, 1f)
            val cy = center + d * rowH
            if (r == 0) {
                drawHeader(f, area, cy, emph, fade, other)
                continue
            }
            val line = f.lines[r - 1]
            val tr = if (s.translation) line.translation?.takeIf { it.isNotBlank() } else null
            val size = otherSize + (currentSize - otherSize) * emph
            // 按最大字号截断：滚动时字号在变，截出来的字不跟着跳
            val text = trim(line.text, area.width, currentSize)
            val gap = 4f
            val blockH = if (tr != null) size + gap + translationSize else size
            val top = cy - blockH / 2f
            val baseline = top + size * 0.86f
            if (r - 1 == f.index) {
                val next = f.lines.getOrNull(r)?.timeMs ?: Long.MAX_VALUE
                var sung = sungCharacters(line, f.positionMs, next)
                if (text != line.text) {
                    // 太长被截成 "..." 时，按截断后能看到的字数换算进度，省略号保持暗色
                    val full = line.text.count { !it.isWhitespace() }.coerceAtLeast(1)
                    val shownCount = text.removeSuffix("...").count { !it.isWhitespace() }
                    sung = (sung * shownCount / full).coerceAtMost(shownCount.toFloat())
                }
                drawLine(text, area, baseline, size, fade, sung, r)
            } else {
                // 刚唱完的那行往上滚时慢慢暗下去
                val lit = otherLit + (0.88f - otherLit) * emph * emph
                drawLine(text, area, baseline, size, (other + (1f - other) * emph) * fade, null, r, lit)
            }
            if (tr != null) {
                drawLine(trim(tr, area.width, translationSize), area, top + size + gap + translationSize * 0.86f, translationSize,
                    (0.35f + 0.4f * emph) * fade * (0.6f + 0.4f * other), null, r, 0.22f + 0.2f * emph)
            }
        }
    }

    /** 第 0 行：歌名 + 歌手（第一句之前、或没有歌词时就停在这里） */
    private fun drawHeader(f: Frame, area: LayoutBounds, cy: Float, emph: Float, fade: Float, other: Float) {
        val titleSize = 16f
        val artistSize = 11.5f
        val gap = 4f
        val hasArtist = f.artist.isNotBlank()
        val top = cy - (if (hasArtist) titleSize + gap + artistSize else titleSize) / 2f
        val title = trim("♪ " + f.title, area.width, titleSize)
        drawLine(title, area, top + titleSize * 0.86f, titleSize, (other + (1f - other) * emph) * fade, null, 0, 0.45f + 0.4f * emph)
        if (hasArtist) {
            drawLine(trim(f.artist, area.width, artistSize), area, top + titleSize + gap + artistSize * 0.86f,
                artistSize, (0.4f + 0.3f * emph) * fade, null, 0, 0.3f)
        }
    }

    /**
     * 画一行字（左对齐 / 右侧时右对齐），逐字上色；[row] 是第几行（彩虹、极光每行错开一点）。
     * [sung] 不为 null = 当前行逐字点亮：唱过的字最亮，正在唱的那个字按进度变亮，没唱到的偏暗；
     * 否则整行都是 [lit] 的亮度
     */
    private fun drawLine(text: String, area: LayoutBounds, baseline: Float, size: Float, alpha: Float, sung: Float?, row: Int, lit: Float = 1f) {
        if (text.isEmpty() || alpha <= 0.005f) return
        val width = tc.smoothWidth(text, size, FONT)
        val x0 = if (alignRight) area.right - width else area.x
        val dim = 0.42f
        val whole = sung?.coerceAtLeast(0f)?.toInt() ?: 0
        val frac = (sung?.coerceAtLeast(0f) ?: 0f) - whole
        tc.push()
        try {
            tc.translate(x0, baseline)
            // 斜体按这一行的基线错切：每行自己倒，整列还是直的
            if (italicTan != 0f) tc.skewX(italicTan)
            var pen = 0f
            var counted = 0
            var i = 0
            while (i < text.length) {
                // 代理对（emoji 等）当一个字
                val n = if (Character.isHighSurrogate(text[i]) && i + 1 < text.length) 2 else 1
                val ch = text.substring(i, i + n)
                val cw = tc.smoothWidth(ch, size, FONT)
                if (!ch[0].isWhitespace()) {
                    var l = lit
                    var a = alpha
                    if (sung != null) {
                        when {
                            counted < whole -> l = 1f
                            counted == whole -> l = dim + (1f - dim) * frac
                            else -> { l = dim; a = alpha * 0.85f }
                        }
                    }
                    setColor((pen + cw / 2f) / width.coerceAtLeast(1f), row)
                    ink(ch, pen, size, l, a)
                    counted++
                }
                pen += cw
                i += n
            }
        } finally {
            tc.pop()
        }
    }

    /**
     * 遮罩颜色约定：alpha = 字的覆盖，rgb = 这个字的颜色（[setColor] 设好，最亮通道是 1）× 亮度。
     * 出现 / 消失的渐变乘在覆盖上：字、光晕一起淡，做不了玻璃直接画字时也一样会淡出
     */
    private fun ink(text: String, x: Float, size: Float, lit: Float, alpha: Float) {
        val l = lit.coerceIn(0f, 1f)
        val a = (alpha * appear).coerceIn(0f, 1f)
        tc.color(rgb[0] * l, rgb[1] * l, rgb[2] * l, a)
        tc.smoothText(text, x, 0f, size, FONT)
    }

    /** 第 [row] 行里 [u]（0 = 行首，1 = 行尾）处那个字的颜色写进 [rgb]（最亮的通道调成 1） */
    private fun setColor(u: Float, row: Int) {
        when (colorMode) {
            COCKPIT_CUSTOM -> { rgb[0] = customR; rgb[1] = customG; rgb[2] = customB }
            COCKPIT_GRADIENT -> if (palette.size >= 2) paletteAt(u.coerceIn(0f, 1f) * (palette.size - 1), false) else accent()
            COCKPIT_RAINBOW -> hsv(frac(u * 0.55f + row * 0.06f + timeSec * 0.12f), 0.72f)
            COCKPIT_AURORA -> if (palette.size >= 2) {
                paletteAt(frac(u * 0.6f - timeSec * 0.08f + row * 0.05f) * palette.size, true)
            } else {
                // 只有一种封面色：在它和旁边的色相之间来回流动
                accent()
                val t = (kotlin.math.sin((u * 3f - timeSec * 0.9f + row * 0.4f).toDouble()).toFloat() + 1f) / 2f
                val r0 = rgb[0]; val g0 = rgb[1]; val b0 = rgb[2]
                rgb[0] = r0 + (b0 - r0) * t * 0.6f
                rgb[1] = g0 + (r0 - g0) * t * 0.6f
                rgb[2] = b0 + (g0 - b0) * t * 0.6f
                normalized(rgb[0], rgb[1], rgb[2], rgb)
            }
            else -> accent()
        }
    }

    private fun accent() {
        rgb[0] = glowR; rgb[1] = glowG; rgb[2] = glowB
    }

    /** 封面色板上第 [at] 个位置（小数在相邻两个颜色之间插值）；[wrap] = 最后一个接回第一个 */
    private fun paletteAt(at: Float, wrap: Boolean) {
        val n = palette.size
        val i = floor(at).toInt()
        val t = at - i
        val a = palette[((i % n) + n) % n]
        val b = palette[if (wrap) (((i + 1) % n) + n) % n else (i + 1).coerceIn(0, n - 1)]
        normalized(
            mix((a shr 16 and 0xFF) / 255f, (b shr 16 and 0xFF) / 255f, t),
            mix((a shr 8 and 0xFF) / 255f, (b shr 8 and 0xFF) / 255f, t),
            mix((a and 0xFF) / 255f, (b and 0xFF) / 255f, t),
            rgb,
        )
    }

    /** 色相 [h]（0..1）、饱和度 [s] 的亮色写进 [rgb] */
    private fun hsv(h: Float, s: Float) {
        val hh = frac(h) * 6f
        val i = hh.toInt()
        val f = hh - i
        val p = 1f - s
        val q = 1f - s * f
        val t = 1f - s * (1f - f)
        when (i) {
            0 -> { rgb[0] = 1f; rgb[1] = t; rgb[2] = p }
            1 -> { rgb[0] = q; rgb[1] = 1f; rgb[2] = p }
            2 -> { rgb[0] = p; rgb[1] = 1f; rgb[2] = t }
            3 -> { rgb[0] = p; rgb[1] = q; rgb[2] = 1f }
            4 -> { rgb[0] = t; rgb[1] = p; rgb[2] = 1f }
            else -> { rgb[0] = 1f; rgb[1] = p; rgb[2] = q }
        }
    }

    /** 引导线：外侧一条两头淡出的细线，当前行的位置有一个亮刻度（跟着滚动走） */
    private fun drawGuide(pass: GlassTextPass, f: Frame, area: LayoutBounds, center: Float, rowH: Float) {
        val gx = if (alignRight) area.right + 14f else area.x - 14f
        val lw = 1.4f
        val halfH = area.height / 2f
        val a = appear
        val line = 0.35f
        setColor(0.5f, f.index + 1)
        pass.bar(gx - lw / 2f, area.y, lw, halfH, rgb[0] * line, rgb[1] * line, rgb[2] * line, 0f, 0.5f * a)
        pass.bar(gx - lw / 2f, center, lw, halfH, rgb[0] * line, rgb[1] * line, rgb[2] * line, 0.5f * a, 0f)
        val cy = center + ((f.index + 1).toFloat() - scroll) * rowH
        if (abs(cy - center) < halfH) {
            val tw = 3f
            val th = rowH * 0.42f
            pass.bar(gx - tw / 2f, cy - th / 2f, tw, th, rgb[0], rgb[1], rgb[2], 0.95f * a, 0.95f * a)
        }
    }

    /**
     * 惯性：转视角时整列往反方向滞后一点，跳起 / 落下时上下沉浮，停下后带一点回弹归位（像头盔显示器跟着头晃）。
     * 偏多少看转得多快（有上限，猛甩鼠标也只偏这么多），由一个略欠阻尼的弹簧带着走。
     */
    private fun updateSway(dt: Float, strength: Float, gap: Boolean) {
        val p = DangoClient.platform.player
        if (strength <= 0f || p == null) {
            swayYaw = Float.NaN
            swayX = 0f; swayY = 0f; swayVX = 0f; swayVY = 0f
            turnYaw = 0f; turnPitch = 0f; rise = 0f
            return
        }
        val camY = DangoClient.gfx.world.cameraY
        if (!p.yaw.isFinite() || !p.pitch.isFinite() || camY.isNaN()) {
            swayYaw = Float.NaN
            turnYaw = 0f; turnPitch = 0f; rise = 0f
        } else if (gap || swayYaw.isNaN()) {
            turnYaw = 0f; turnPitch = 0f; rise = 0f
        } else if (dt > 0f) {
            var dYaw = (p.yaw - swayYaw) % 360f
            if (dYaw > 180f) dYaw -= 360f
            if (dYaw < -180f) dYaw += 360f
            val dPitch = p.pitch - swayPitch
            val dRise = (camY - swayCamY).toFloat()
            if (abs(dYaw) > 60f || abs(dPitch) > 60f || abs(dRise) > 3f) {
                // 一帧转了 60 度以上 / 挪了 3 格以上：传送、重生、换维度、切换视角，不算，免得整列被甩飞
                turnYaw = 0f; turnPitch = 0f; rise = 0f
            } else {
                // 鼠标是一帧一帧动的：速度先平滑一下，再拿去推弹簧
                val kt = 1f - exp(-dt / 0.06f)
                turnYaw += (dYaw / dt - turnYaw) * kt
                turnPitch += (dPitch / dt - turnPitch) * kt
                rise += (dRise / dt - rise) * (1f - exp(-dt / 0.08f))
            }
        }
        swayYaw = p.yaw
        swayPitch = p.pitch
        swayCamY = camY

        // 往右转（yaw 变大）→ 往左偏；低头（pitch 变大）→ 往上偏；往上跳 → 往下沉，落地时回弹一下
        val reach = SWAY_PX * strength.coerceIn(0f, 1f)
        val tx = -reach * tanh(turnYaw / 220f)
        val ty = (-0.7f * tanh(turnPitch / 220f) + 0.6f * tanh(rise / 6f)) * reach
        var left = dt
        while (left > 1e-5f) {
            val h = min(left, 1f / 240f)
            left -= h
            swayVX += (SWAY_K * (tx - swayX) - SWAY_C * swayVX) * h
            swayVY += (SWAY_K * (ty - swayY) - SWAY_C * swayVY) * h
            swayX += swayVX * h
            swayY += swayVY * h
        }
        // 保险：数值出问题时归零，不让列表飞走
        if (!swayX.isFinite() || !swayY.isFinite()) {
            swayX = 0f; swayY = 0f; swayVX = 0f; swayVY = 0f
        }
    }

    /** 按 [size] 截断到 [maxW] 以内（结果缓存，换歌时清空） */
    private fun trim(s: String, maxW: Float, size: Float): String {
        val key = "${size.toBits()}|${maxW.toBits()}|$s"
        trimCache[key]?.let { return it }
        val out = if (tc.smoothWidth(s, size, FONT) <= maxW) s else {
            var end = s.length
            while (end > 0 && tc.smoothWidth(s.substring(0, end) + "...", size, FONT) > maxW) end--
            // 不切在代理对中间
            if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
            s.substring(0, end) + "..."
        }
        if (trimCache.size > 512) trimCache.clear()
        trimCache[key] = out
        return out
    }

    private companion object {
        /** 驾驶舱用粗一点的平滑字体 */
        const val FONT = CjkStyle.BOLD
        /** 一次显示几行（含上下渐隐的） */
        const val VISIBLE_ROWS = 7
        /** 一行多高（逻辑像素）：只有原文 / 原文下面还有翻译 */
        const val ROW_H = 32f
        const val ROW_H_TRANSLATED = 44f
        /** 一行最宽多少（逻辑像素），太长的截成 "..." */
        const val WIDTH = 300f
        /** 透视倾斜（度）：外侧靠近、往屏幕中间退远 */
        const val TILT = 18f
        /** 斜体（度） */
        const val ITALIC = 8f
        /** 当前行以外的行有多明显（0.1..1） */
        const val OTHER_LINES = 0.45f
        /** 发光强弱（0..1） */
        const val GLOW_STRENGTH = 0.6f
        /** 玻璃字的折射强弱（0..1） */
        const val REFRACTION = 0.2f

        /** 惯性：强度、最多偏多少（逻辑像素）；弹簧约 2.4 Hz、阻尼比约 0.55（停下时轻轻回弹一下） */
        const val INERTIA_STRENGTH = 0.5f
        const val SWAY_PX = 26f
        const val SWAY_K = 227f
        const val SWAY_C = 16.6f

        fun frac(x: Float): Float = x - floor(x)
        fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        /** 调成最亮的通道是 1（很暗 / 黑色当白色）：遮罩里要靠这个分出颜色和亮度 */
        fun normalized(r: Float, g: Float, b: Float, out: FloatArray) {
            val peak = maxOf(r, g, b)
            if (peak < 0.05f) {
                out[0] = 1f; out[1] = 1f; out[2] = 1f
                return
            }
            out[0] = (r / peak).coerceIn(0f, 1f)
            out[1] = (g / peak).coerceIn(0f, 1f)
            out[2] = (b / peak).coerceIn(0f, 1f)
        }

        fun ktvEndMs(line: LyricLine, nextLineMs: Long): Long {
            val lastWord = line.words?.maxOfOrNull { it.startMs + it.durMs.coerceAtLeast(1L) }
            return lastWord?.coerceAtLeast(line.timeMs + 1L) ?: nextLineMs.coerceAtLeast(line.timeMs + 1L)
        }

        /** 这一句唱到第几个字（不算空白，小数部分是正在唱的那个字唱了多少）；没有逐字时间就按整句时长均分 */
        fun sungCharacters(line: LyricLine, positionMs: Long, nextLineMs: Long): Float {
            val length = line.text.count { !it.isWhitespace() }
            if (length == 0 || positionMs < line.timeMs) return 0f
            val timed = line.words.orEmpty()
            if (timed.isEmpty()) {
                // 没有下一句的时间：一开始就整句点亮
                if (nextLineMs == Long.MAX_VALUE) return length.toFloat()
                return length * ((positionMs - line.timeMs).toFloat() / (ktvEndMs(line, nextLineMs) - line.timeMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
            }
            var sung = 0f
            for (word in timed) {
                val count = word.text.count { !it.isWhitespace() }
                val progress = ((positionMs - word.startMs).toFloat() / word.durMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                sung += count * progress
                if (progress < 1f) break
            }
            return sung.coerceAtMost(length.toFloat())
        }
    }
}

/** 驾驶舱配色（顺序和设置页的选项一致） */
internal const val COCKPIT_COVER = 0
internal const val COCKPIT_CUSTOM = 1
internal const val COCKPIT_GRADIENT = 2
internal const val COCKPIT_RAINBOW = 3
internal const val COCKPIT_AURORA = 4
