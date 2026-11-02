package me.sorihiko.dango.lyrics

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.gfx.CjkStyle

import java.util.Random
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 文闪：歌词一个词一个词地砸到屏幕中间，叠成一块"等宽排版"的字块
 * （每一行自动缩放到同样宽，短的字大、长的字小），配合：
 *   - 新词出现时整行弹一下
 *   - 部分词先以超大、倾斜的样子"砸"出来，再落回字块里
 *   - 每句随机白字或红色发光字
 *   - 内置播放器放歌时，鼓点让字块跳动、抖动
 * 有网易云逐字歌词时按每个字的真实时间出现，没有就把一句平均拆开。
 */
class TextFlash {

    private val canvas get() = DangoClient.gfx.text

    private class Piece(val text: String, val startMs: Long, val slam: Boolean)

    private class Row(val units: MutableList<Piece> = mutableListOf()) {
        var size = 0f          // 当前显示字号（平滑过渡到目标字号）
        fun visibleText(pos: Long) = units.filter { it.startMs <= pos }.joinToString(" ") { it.text }
        fun newestStart(pos: Long) = units.filter { it.startMs <= pos }.maxOfOrNull { it.startMs } ?: Long.MIN_VALUE
    }

    private class Block(
        val lineTime: Long,
        val rows: List<Row>,
        val red: Boolean,
        val glow: Boolean,
        val tilt: Float,
        /** 这块是不是"只显示翻译"画出来的：开关变了要重做整块 */
        val only: Boolean,
    ) {
        var fadeStartNanos = 0L
    }

    private val random = Random()
    private var current: Block? = null
    private val fading = mutableListOf<Block>()
    private var lastNanos = 0L

    fun reset() {
        current = null
        fading.clear()
    }

    // ---------------------------------------------------------------- 拆词

    private fun isCjk(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA ||
            b == Character.UnicodeBlock.HANGUL_SYLLABLES || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }

    /** 把一句拆成词：英文按空格；中日韩每两个字一组 */
    private fun tokens(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var cjkRun = StringBuilder()
        fun flushWord() {
            if (sb.isNotBlank()) out += sb.toString().trim()
            sb.setLength(0)
        }
        fun flushCjk() {
            var i = 0
            while (i < cjkRun.length) {
                out += cjkRun.substring(i, min(cjkRun.length, i + 2))
                i += 2
            }
            cjkRun = StringBuilder()
        }
        for (c in text) {
            when {
                isCjk(c) -> {
                    flushWord(); cjkRun.append(c)
                }
                c.isWhitespace() || c in "，。！？、,.!?;；:：\"“”" -> {
                    flushWord(); flushCjk()
                }
                else -> {
                    flushCjk(); sb.append(c)
                }
            }
        }
        flushWord(); flushCjk()
        return out
    }

    private fun shape(s: String) = s.uppercase().trim { it.isWhitespace() || it in "，。,.!?！？" }

    /** 显示长度：中日韩字算 2 个 */
    private fun len(s: String): Int {
        var n = 0
        for (c in s) n += if (isCjk(c)) 2 else 1
        return n
    }

    private fun build(line: LyricLine, endMs: Long, onlyTranslation: Boolean): Block {
        // 只显示翻译：这一句有翻译就整句改用翻译（翻译没有逐字时间，按一句平均拆开）
        val translated = if (onlyTranslation) line.translation?.takeIf { it.isNotBlank() } else null
        val text = translated ?: line.text
        val units = mutableListOf<Piece>()
        val words = if (translated != null) null else line.words
        if (words != null) {
            // 逐字歌词：英文词直接用；连续的中日韩单字两两合并
            var pendingText = ""
            var pendingStart = 0L
            for (w in words) {
                val t = shape(w.text)
                if (t.isEmpty()) continue
                if (t.length == 1 && isCjk(t[0])) {
                    if (pendingText.isEmpty()) pendingStart = w.startMs
                    pendingText += t
                    if (pendingText.length >= 2) {
                        units += Piece(pendingText, pendingStart, false)
                        pendingText = ""
                    }
                } else {
                    if (pendingText.isNotEmpty()) {
                        units += Piece(pendingText, pendingStart, false); pendingText = ""
                    }
                    units += Piece(t, w.startMs, false)
                }
            }
            if (pendingText.isNotEmpty()) units += Piece(pendingText, pendingStart, false)
        } else {
            // 普通歌词：把一句平均拆开
            val toks = tokens(text).map { shape(it) }.filter { it.isNotEmpty() }
            val dur = (endMs - line.timeMs).coerceAtLeast(300L)
            val span = min(dur * 0.8, toks.size * 380.0)
            toks.forEachIndexed { i, t ->
                units += Piece(t, line.timeMs + (span * i / max(1, toks.size)).toLong(), false)
            }
        }

        // 随机挑一些较长的词做"砸出来"的效果
        val withSlam = units.map { u ->
            if (len(u.text) >= 3 && random.nextFloat() < 0.22f) Piece(u.text, u.startMs, true) else u
        }

        // 分行：短词跟在前面，长词另起一行
        val rows = mutableListOf<Row>()
        var row = Row()
        var rowLen = 0
        for (u in withSlam) {
            val l = len(u.text)
            val fits = row.units.isEmpty() ||
                (l <= 3 && rowLen + 1 + l <= 8) ||
                (rowLen <= 3 && rowLen + 1 + l <= 9)
            if (!fits) {
                rows += row
                row = Row()
                rowLen = 0
            }
            row.units += u
            rowLen += if (rowLen == 0) l else l + 1
        }
        if (row.units.isNotEmpty()) rows += row

        val red = random.nextFloat() < 0.32f
        return Block(line.timeMs, rows, red, red || random.nextFloat() < 0.35f, (random.nextFloat() * 2f - 1f) * 12f, translated != null)
    }

    // ---------------------------------------------------------------- 绘制

    /**
     * @param pos   歌曲内时间（已加偏移）
     * @param beat  鼓点冲击强度 0..1（只有内置播放器有）
     * @param onlyTranslation 选了"只显示翻译"：有翻译就只砸翻译出来
     */
    fun draw(lyrics: Lyrics?, pos: Long, beat: Float, sizeMul: Float, onlyTranslation: Boolean = false) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 16f else ((now - lastNanos) / 1e6f).coerceIn(0f, 50f)
        lastNanos = now

        // 当前这句
        if (lyrics != null) {
            val idx = lyrics.indexAt(pos)
            val line = lyrics.lines.getOrNull(idx)
            val lineTime = line?.timeMs ?: Long.MIN_VALUE
            if (current?.lineTime != lineTime || current?.only != onlyTranslation) {
                current?.let { it.fadeStartNanos = now; fading += it }
                current = if (line != null && line.text.isNotBlank()) {
                    val end = lyrics.lines.getOrNull(idx + 1)?.timeMs ?: (line.timeMs + 5000)
                    build(line, end, onlyTranslation)
                } else null
            }
        } else if (current != null) {
            current?.let { it.fadeStartNanos = now; fading += it }
            current = null
        }
        if (current == null && fading.isEmpty()) return

        val sw = DangoClient.platform.windowWidth.toFloat()
        val sh = DangoClient.platform.windowHeight.toFloat()
        val unitW = min(sw, sh * 16f / 9f) * 0.17f * sizeMul

        canvas.beginScreen()
        try {
            val it = fading.iterator()
            while (it.hasNext()) {
                val b = it.next()
                val t = (now - b.fadeStartNanos) / 1e9f / 0.14f
                if (t >= 1f) {
                    it.remove(); continue
                }
                drawBlock(b, Long.MAX_VALUE, sw / 2f, sh * 0.46f, sh * 0.42f, unitW, 0f, 1f - t, 1f + t * 0.08f, dt)
            }
            current?.let { drawBlock(it, pos, sw / 2f, sh * 0.46f, sh * 0.42f, unitW, beat, 1f, 1f, dt) }
        } finally {
            canvas.endScreen()
        }
    }

    /** 以 (cx, cy) 为中心画一个字块；maxSlam = "砸"出来的词最大字号 */
    private fun drawBlock(b: Block, pos: Long, cx: Float, cy: Float, maxSlam: Float, w: Float, beat: Float, alpha: Float, extraScale: Float, dt: Float) {
        // 正在"砸"的词：单独超大显示，这期间字块先不画
        if (pos != Long.MAX_VALUE) {
            val slamming = b.rows.flatMap { it.units }.lastOrNull { it.slam && it.startMs <= pos && pos - it.startMs < 240 }
            if (slamming != null) {
                drawSlam(b, slamming, pos, cx, cy, maxSlam, w, beat, alpha)
                return
            }
        }

        val visible = b.rows.filter { r -> r.units.any { it.startMs <= pos } }.takeLast(4)
        if (visible.isEmpty()) return

        // 每一行缩放到同样宽
        val sizes = visible.map { r ->
            val text = r.visibleText(pos)
            val w1 = canvas.smoothWidth(text, 1f, CjkStyle.DISPLAY).coerceAtLeast(0.01f)
            val target = (w / w1).coerceIn(w * 0.16f, w * 0.62f)
            r.size = if (r.size <= 0f) target else r.size + (target - r.size) * (1f - exp(-dt / 60f))
            r.size
        }
        val gap = w * 0.02f
        val heights = sizes.map { it * 0.86f }
        val total = heights.sum() + gap * (visible.size - 1)

        val pulse = 1f + 0.09f * beat
        val shakeX = (random.nextFloat() - 0.5f) * beat * w * 0.05f
        val shakeY = (random.nextFloat() - 0.5f) * beat * w * 0.05f

        canvas.push()
        canvas.translate(cx + shakeX, cy + shakeY)
        canvas.scale(pulse * extraScale, pulse * extraScale)

        var y = -total / 2f
        visible.forEachIndexed { i, r ->
            val text = r.visibleText(pos)
            val size = sizes[i]
            // 新词出现时这一行弹一下
            val age = if (pos == Long.MAX_VALUE) 1000L else pos - r.newestStart(pos)
            val pop = popScale(age)
            val tw = canvas.smoothWidth(text, size, CjkStyle.DISPLAY)
            val rowCy = y + heights[i] / 2f
            canvas.push()
            canvas.translate(0f, rowCy)
            canvas.scale(pop, pop)
            drawText(text, -tw / 2f, size * 0.40f, size, b, alpha)
            canvas.pop()
            y += heights[i] + gap
        }
        canvas.pop()
    }

    /** 超大倾斜的词，从更大缩回来，快结束时缩小淡出（随后落回字块） */
    private fun drawSlam(b: Block, u: Piece, pos: Long, cx: Float, cy: Float, maxSize: Float, w: Float, beat: Float, alphaMul: Float) {
        val age = (pos - u.startMs).toFloat()
        val t = (age / 240f).coerceIn(0f, 1f)
        val w1 = canvas.smoothWidth(u.text, 1f, CjkStyle.DISPLAY).coerceAtLeast(0.01f)
        val size = (w * 3.2f / w1).coerceAtMost(maxSize)
        val intro = 1f + 0.35f * exp(-age / 45f)
        val outro = if (t > 0.75f) 1f - (t - 0.75f) / 0.25f * 0.55f else 1f
        val alpha = (if (t > 0.85f) 1f - (t - 0.85f) / 0.15f else 1f) * alphaMul
        val s = intro * outro * (1f + 0.06f * beat)
        val tw = canvas.smoothWidth(u.text, size, CjkStyle.DISPLAY)

        canvas.push()
        canvas.translate(cx, cy)
        canvas.rotate(b.tilt)
        canvas.scale(s, s)
        drawText(u.text, -tw / 2f, size * 0.40f, size, b, alpha)
        canvas.pop()
    }

    private fun popScale(ageMs: Long): Float {
        if (ageMs < 0) return 1f
        val t = (ageMs / 160f).coerceIn(0f, 1f)
        // 从 1.28 回弹到 1（带一点点过冲）
        val back = 1f - t
        return 1f + 0.28f * back * back - 0.04f * sin(t * Math.PI.toFloat()) * (1f - t)
    }

    /** 画一行字：发光（多次偏移的半透明描边）+ 本体 */
    private fun drawText(text: String, x: Float, baseline: Float, size: Float, b: Block, alpha: Float) {
        val r: Float
        val g: Float
        val bl: Float
        if (b.red) {
            r = 1f; g = 0.17f; bl = 0.33f
        } else {
            r = 1f; g = 1f; bl = 1f
        }
        if (b.glow) {
            val rings = if (b.red) listOf(0.10f to 0.05f, 0.05f to 0.10f) else listOf(0.06f to 0.07f)
            for ((rad, a) in rings) {
                canvas.color(r, g, bl, a * alpha)
                for (k in 0 until 8) {
                    val ang = k / 8f * Math.PI.toFloat() * 2f
                    canvas.smoothText(text, x + cos(ang) * size * rad, baseline + sin(ang) * size * rad, size, CjkStyle.DISPLAY)
                }
            }
        }
        canvas.color(r, g, bl, alpha)
        canvas.smoothText(text, x, baseline, size, CjkStyle.DISPLAY)
    }
}
