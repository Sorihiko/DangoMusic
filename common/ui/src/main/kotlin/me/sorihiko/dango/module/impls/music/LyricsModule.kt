package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.WorldRenderEndEvent
import me.sorihiko.dango.gfx.CjkStyle
import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.lyrics.LyricSplit
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider
import me.sorihiko.dango.lyrics.TextFlash
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.CoverArt
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.playback.PlaybackSessions
import me.sorihiko.dango.playback.SessionState
import me.sorihiko.dango.playback.SessionTrack

import java.util.Random
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** "Above ground"：歌词中心最低在眼睛下面多少格 */
private const val GROUND_MARGIN = 0.5

/** Stay on screen 弹簧的快慢（越大跟得越紧；12 大约 0.3 秒回到位） */
private const val SPRING = 12.0

/**
 * 歌词：内置播放器放歌时，从网易云取（逐字）歌词，一句句以动画漂浮在世界里。
 * 另外负责左上角原版风格的"正在播放"（Now playing / Cover）。
 */
@ModuleMeta(Category.MUSIC, "Lyrics")
class LyricsModule : Module() {

    @ModuleValue("3D lyrics", "Show lyrics floating in the world")
    private val worldMode = BooleanValue(true)

    /** 快捷键"开关歌词"用 */
    fun toggleLyrics() = worldMode.toggle()

    @ModuleValue("Now playing", "Show the current song in the top left")
    private val nowPlaying = BooleanValue(false)

    @ModuleValue("Cover", "Show the album cover in the top left")
    private val showCover = BooleanValue(true)

    @ModuleValue("Scale", "Lyrics size")
    private val scale = FloatValue(0.5f, 3f, 1f, 1)

    @ModuleValue("Distance", "How far away 3D lyrics appear (blocks)")
    private val distance = FloatValue(3f, 40f, 10f, 1)

    @ModuleValue("Glow", "Deep Glow style: white-hot core, wide soft halo")
    private val glow = BooleanValue(true)

    @ModuleValue("Glow color", "Glow tint: text color, pink, blue or gold")
    private val glowColor = IntValue(0, 3, 0)

    @ModuleValue("Glow strength", "Glow brightness")
    private val glowStrength = FloatValue(0f, 3f, 0.3f, 2)

    @ModuleValue("Glow radius", "Halo spread: higher is wider and softer")
    private val glowRadius = FloatValue(0f, 1f, 0.5f, 2)

    @ModuleValue("Smooth font", "Smooth font instead of the Minecraft pixel font")
    private val smoothFont = BooleanValue(false)

    @ModuleValue("Wiggle", "Low-fps jitter on every letter, like hand-drawn animation")
    private val wiggle = BooleanValue(true)

    @ModuleValue("Wiggle fps", "Jitter frame rate: lower feels more hand-drawn")
    private val wiggleFps = IntValue(4, 24, 8)

    @ModuleValue("Wiggle amount", "Jitter strength")
    private val wiggleAmount = FloatValue(0f, 2f, 0.8f, 2)

    @ModuleValue("Text flash", "Words slam onto the screen one by one, synced to the beat")
    private val textFlash = BooleanValue(false)

    @ModuleValue("Word pop", "Words pop in one by one as they are sung")
    private val wordPop = BooleanValue(true)

    @ModuleValue("Flash size", "Text flash size")
    private val flashSize = FloatValue(0.5f, 2f, 1f, 2)

    @ModuleValue("Face player", "Lyrics keep turning to face you")
    private val facePlayer = BooleanValue(true)

    @ModuleValue("Keep away", "Lyrics back off when you walk or sprint into them")
    private val keepAway = BooleanValue(true)

    @ModuleValue("Stay on screen", "Lyrics glide back into view when you turn away, so they never leave the screen")
    private val stayOnScreen = BooleanValue(false)

    @ModuleValue("Above ground", "Lyrics never appear below your eyes, so they don't sink into the ground")
    private val aboveGround = BooleanValue(false)

    @ModuleValue("Spread", "How far left or right each line can appear (degrees)")
    private val spread = FloatValue(0f, 60f, 30f, 0)

    @ModuleValue("Split lines", "Split two lines written as one (NetEase often does this) so they appear separately")
    private val splitLines = BooleanValue(true)

    // ---------------------------------------------------------------- 状态

    /** 正在播放的歌（来自当前播放会话：内置或 Salt） */
    private class NowPlaying(val track: SessionTrack, val playing: Boolean) {
        val key: String get() = track.key
        val title: String get() = track.title
        val artist: String get() = track.artistText
    }

    private val cover = CoverArt()

    private var current: NowPlaying? = null
    private var currentKey: String? = null
    private var songPos = 0.0                 // 毫秒，本地平滑时钟
    private var lastSeenNanos = 0L
    private var lastLyrics: Lyrics? = null
    private var lastIndex = -1

    private val random = Random()

    /** 一句漂浮的歌词 */
    private class Floating(
        val lines: List<String>,
        val startMs: Long,
        val revealMs: Long,
        var x: Double, var y: Double, var z: Double,
        var yaw: Float, var pitch: Float,
        /** 每个非空白字符出现的时间（相对这句开始，毫秒），按词闪出时用 */
        val charTimes: LongArray = LongArray(0),
    ) {
        var fadeStartNanos = 0L
        /** Stay on screen 往回拉时的角速度（度 / 秒），用弹簧算，起步和停下都是平滑的 */
        var vYaw = 0.0
        var vPitch = 0.0
        /** 暂停后恢复播放时重新出现的这句：用一个短淡入代替逐字出现 */
        var resumedAt = 0L
    }

    /** 恢复播放后重新出现的那句歌词，0.3 秒淡入 */
    private fun appearOf(f: Floating): Float =
        if (f.resumedAt == 0L) 1f else ((System.nanoTime() - f.resumedAt) / 3e8f).coerceIn(0f, 1f)

    private var active: Floating? = null
    /** 左上角小柱子当前颜色（向封面主色渐变） */
    private var barR = 0xF2 / 255f
    private var barG = 0xA7 / 255f
    private var barB = 0xC3 / 255f
    private var barColorNanos = 0L
    /** 暂停期间（歌词先隐藏，恢复播放时再出现） */
    private var wasPaused = false
    private val flash = TextFlash()
    /** 正在往发光画布上画（只画字本身，不画阴影） */
    private var glowPass = false
    private val fading = mutableListOf<Floating>()

    override fun onDisable() {
        cover.clear()
        current = null
        currentKey = null
        active = null
        fading.clear()
        flash.reset()
        lastIndex = -1
    }

    // ---------------------------------------------------------------- 当前歌曲 / 时钟

    /** 从当前播放会话读取（内置或 Salt 同一入口） */
    private fun nowPlaying(): NowPlaying? {
        val snap = PlaybackSessions.active().snapshot()
        val t = snap.track ?: return null
        // 有曲目且状态未结束/出错/停止时显示；UNKNOWN（Salt 刚启用）也显示
        val active = when (snap.state) {
            SessionState.LOADING, SessionState.PLAYING, SessionState.PAUSED, SessionState.UNKNOWN -> true
            else -> false
        }
        if (!active) return null
        return NowPlaying(t, snap.state == SessionState.PLAYING)
    }

    private fun lyricsFor(): Lyrics? = PlaybackSessions.active().lyrics()

    private fun requestLyrics() {
        PlaybackSessions.active().requestLyrics()
    }

    /** 跟着播放会话的进度走（内置为精确值；Salt 为锚点外推的显示位置） */
    private fun updateClock() {
        val now = System.nanoTime()
        val s = nowPlaying()
        if (s == null) {
            // 超过 3 秒没在放歌就清掉
            if (current != null && (now - lastSeenNanos) / 1e9 > 3.0) {
                current = null
                currentKey = null
                active?.let { startFade(it) }
                active = null
                lastIndex = -1
            }
            return
        }
        lastSeenNanos = now
        val p = PlaybackSessions.active().snapshot().positionMs.toDouble()
        if (s.key != currentKey) {
            currentKey = s.key
            songPos = p
            active?.let { startFade(it) }
            active = null
            lastIndex = -1
            requestLyrics()
        } else {
            if (abs(p - songPos) > 300) lastIndex = -1   // 拖了进度条 / seek
            songPos = p
            // 歌词可能晚到：没拿到就继续要（会话源内部幂等）
            if (lyricsFor() == null) requestLyrics()
        }
        current = s
    }

    // ---------------------------------------------------------------- 渲染

    /** 主菜单复用简洁的 Now playing，不启动世界歌词或修改 HUD 开关。 */
    fun renderMainMenu() {
        if (!DangoClient.platform.inMainMenu) return
        val s = nowPlaying() ?: return
        cover.requestTrack(s.track)
        DangoClient.gfx.guarded {
            cover.upload()
            drawNowPlaying(s, null)
        }
    }

    @EventHandler
    fun render(e: WorldRenderEndEvent) {
        if (!enabled) return
        updateClock()
        val s = current
        // 小柱子的颜色取自封面，所以只开 Now playing 也要加载封面
        if (s != null && (showCover.get() || nowPlaying.get())) {
            cover.requestTrack(s.track)
        }
        cover.upload()
        // 拆句开关变了：歌词行的下标都变了，重新找当前这句
        if (LyricSplit.enabled != splitLines.get()) {
            LyricSplit.enabled = splitLines.get()
            lastIndex = -1
        }
        val lyrics = lyricsFor()
        if (lyrics !== lastLyrics) {
            lastLyrics = lyrics
            lastIndex = -1
            active?.let { startFade(it) }
            active = null
        }
        val paused = s != null && !s.playing
        if (s != null && lyrics != null && !lyrics.hasSyncableTimes) {
            // 整首没有可信行时间：提示一次，不用它做同步定位
            if (lastIndex != -2) {
                lastIndex = -2
                active?.let { startFade(it) }
                active = spawn(LyricLine(0L, I18n.text("Lyrics timing unavailable — cannot sync"), null), 5000L)
            }
        } else if (s != null && lyrics != null) {
            val pos = songPos.toLong()
            val idx = lyrics.indexAt(pos)
            if (paused) {
                // 暂停：当前这句淡出消失，恢复播放时再出现
                active?.let { startFade(it) }
                active = null
                lastIndex = idx
                wasPaused = true
            } else if (idx != lastIndex || wasPaused) {
                val resumed = wasPaused && idx == lastIndex
                wasPaused = false
                lastIndex = idx
                active?.let { startFade(it) }
                active = null
                if (idx >= 0) {
                    val line = lyrics.lines[idx]
                    if (line.text.isNotBlank()) {
                        val next = lyrics.lines.getOrNull(idx + 1)?.timeMs ?: (line.timeMs + 5000)
                        active = spawn(line, next).also { if (resumed) it.resumedAt = System.nanoTime() }
                    }
                }
            }
        }

        if (worldMode.get() && !textFlash.get()) keepOnScreen()

        val gfx = DangoClient.gfx
        val deepGlow = gfx.glow
        gfx.guarded {
            val songNow = songPos.toLong()
            val beat = if (s != null) MusicPlayer.beatPulse(MusicPlayer.positionMs) else 0f
            if (!worldMode.get()) {
                // 3D lyrics 关掉 = 不显示歌词
            } else if (textFlash.get()) {
                flash.draw(if (s != null && !paused) lyrics else null, songNow, beat, flashSize.value)
            } else {
                // 先把歌词画到发光画布上做出光晕，再正常画一遍清晰的字
                if (glow.get() && glowStrength.value > 0.01f && deepGlow.available && (active != null || fading.isNotEmpty())) {
                    try {
                        if (deepGlow.begin()) {
                            glowPass = true
                            try {
                                drawWorld(e.partialTicks, songNow, lyrics, beat)
                            } finally {
                                glowPass = false
                            }
                            val (r, g, b) = glowTint()
                            deepGlow.end(r, g, b, glowStrength.value * 4f, glowRadius.value)
                        }
                    } catch (t: Throwable) {
                        // 发光出任何错都不影响歌词本身：关掉发光，下面照常画字
                        glowPass = false
                        deepGlow.disable(t)
                    }
                    // 发光画完，把文字需要的渲染状态重新设好
                    gfx.world.resync()
                }
                drawWorld(e.partialTicks, songNow, lyrics, beat)
            }
            if (nowPlaying.get() && s != null && !songCardCovers()) drawNowPlaying(s, lyrics)
        }
    }

    private fun startFade(f: Floating) {
        f.fadeStartNanos = System.nanoTime()
        fading.add(f)
    }

    /** 拆成"词"：英文按空格；中日韩每两个字一组。返回每个词（去掉空白） */
    private fun splitWords(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var cjk = 0
        fun flush() {
            if (sb.isNotEmpty()) out += sb.toString()
            sb.setLength(0)
            cjk = 0
        }
        for (c in text) {
            when {
                c.isWhitespace() -> flush()
                c.code >= 0x2E80 -> {
                    if (sb.isNotEmpty() && cjk == 0) flush()   // 英文后面跟中文：分开
                    sb.append(c)
                    cjk++
                    if (cjk >= 2) flush()
                }
                else -> {
                    if (cjk > 0) flush()
                    sb.append(c)
                }
            }
        }
        flush()
        return out
    }

    /** 每个非空白字符的出现时间：有逐字歌词用真实时间，没有就把一句平均分给每个词 */
    private fun charTimes(line: LyricLine, nextMs: Long): LongArray {
        val times = ArrayList<Long>()
        val words = line.words
        if (words != null) {
            for (w in words) {
                val at = (w.startMs - line.timeMs).coerceAtLeast(0L)
                for (c in w.text) if (!c.isWhitespace()) times += at
            }
        } else {
            val toks = splitWords(line.text)
            val dur = (nextMs - line.timeMs).coerceAtLeast(300L)
            val span = min(dur * 0.75, toks.size * 330.0)
            toks.forEachIndexed { i, t ->
                val at = (span * i / max(1, toks.size)).toLong()
                repeat(t.length) { times += at }
            }
        }
        return times.toLongArray()
    }

    /**
     * "Above ground"：歌词中心最低只到眼睛下面 [GROUND_MARGIN] 格（站在平地上时离地还有一格多），
     * 换算成距离 [d] 处最多能往下偏多少度（原版 pitch 向下为正）
     */
    private fun maxPitch(d: Double): Float =
        Math.toDegrees(asin((GROUND_MARGIN / d).coerceIn(0.0, 1.0))).toFloat()

    private fun spawn(line: LyricLine, nextMs: Long): Floating {
        val text = line.text
        val startMs = line.timeMs
        val ct = charTimes(line, nextMs)
        val lines = wrap(text, 200)
        val chars = lines.sumOf { it.length }
        val lineDur = (nextMs - startMs).coerceAtLeast(400L)
        val reveal = min((chars * 55L).coerceAtLeast(250L), (lineDur * 0.6).toLong())

        val player = DangoClient.platform.player
        if (player == null) return Floating(lines, startMs, reveal, 0.0, 0.0, 0.0, 0f, 0f, ct)
        val yaw = player.yaw + (random.nextFloat() * 2f - 1f) * spread.value
        val d = distance.value.toDouble()
        var pitch = (player.pitch - 3f - random.nextFloat() * 10f).coerceIn(-60f, 30f)
        if (aboveGround.get()) pitch = min(pitch, maxPitch(d))
        val yr = Math.toRadians(yaw.toDouble())
        val pr = Math.toRadians(pitch.toDouble())
        val dx = -sin(yr) * cos(pr)
        val dy = -sin(pr)
        val dz = cos(yr) * cos(pr)
        val ex = player.eyeX
        val ey = player.eyeY
        val ez = player.eyeZ
        return Floating(lines, startMs, reveal, ex + dx * d, ey + dy * d, ez + dz * d, yaw, pitch, ct)
    }

    /** 按像素宽度换行：英文在空格处断，中日韩可以在任意字之间断 */
    private fun wrap(text: String, maxWidth: Int): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var lastSpace = -1
        for (c in text) {
            sb.append(c)
            if (c == ' ') lastSpace = sb.length - 1
            if (strW(sb.toString()) > maxWidth && sb.length > 1) {
                if (lastSpace > 0) {
                    out += sb.substring(0, lastSpace).trim()
                    val rest = sb.substring(lastSpace + 1)
                    sb.setLength(0)
                    sb.append(rest)
                } else {
                    out += sb.substring(0, sb.length - 1).trim()
                    sb.setLength(0)
                    sb.append(c)
                }
                lastSpace = -1
            }
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    // 暖白偏粉的颜色
    private val textRgb = 0xF7E1D7

    // ---------------------------------------------------------------- 歌词文字（平滑字体 / MC 字体 + 发光）

    /** 平滑字体的字号：和 MC 字体的大写字高（7 像素）差不多 */
    private val lyricSize = 10f

    private fun strW(s: String): Float =
        if (smoothFont.get()) DangoClient.gfx.text.smoothWidth(s, lyricSize, CjkStyle.PLAIN) else DangoClient.gfx.text.pixelWidth(s)

    private fun charW(c: Char): Float =
        if (smoothFont.get()) DangoClient.gfx.text.smoothWidth(c.toString(), lyricSize, CjkStyle.PLAIN) else DangoClient.gfx.text.pixelCharWidth(c)

    /** 光晕颜色（乘在字本身的颜色上）：0 = 跟随字色 */
    private fun glowTint(): Triple<Float, Float, Float> = when (glowColor.value) {
        1 -> Triple(1f, 0.38f, 0.62f)
        2 -> Triple(0.32f, 0.66f, 1f)
        3 -> Triple(1f, 0.74f, 0.30f)
        else -> Triple(1f, 1f, 1f)
    }

    private class Glyph(val c: Char, val x: Float, val y: Float, val a: Float, val k: Int, val pop: Float = 1f)

    /** 由 (字序号, 第几帧, 种类) 得到一个固定的 -1..1 随机数：同一帧里不管画几遍（发光层、本体）结果都一样 */
    private fun hashRand(k: Int, frame: Long, salt: Int, seed: Int): Float {
        var h = k * 73856093 xor (frame * 19349663L).toInt() xor (salt * 83492791) xor seed
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return (h and 0xFFFF) / 32767.5f - 1f
    }

    /**
     * 低帧率 wiggle：每个字每 1/fps 秒换一个随机的小角度、小错位、小缩放，中间保持不动，
     * 就是 AE 里 posterizeTime(8) + wiggle 的一顿一顿手绘感。
     */
    private inline fun withWiggle(g: Glyph, seed: Int, frame: Long, draw: () -> Unit) {
        val amt = wiggleAmount.value
        val wig = wiggle.get() && amt > 0.001f
        if (!wig && g.pop == 1f) {
            draw()
            return
        }
        val rot = if (wig) hashRand(g.k, frame, 1, seed) * 9f * amt else 0f
        val dx = if (wig) hashRand(g.k, frame, 2, seed) * 0.8f * amt else 0f
        val dy = if (wig) hashRand(g.k, frame, 3, seed) * 1.6f * amt else 0f
        val sc = (if (wig) 1f + hashRand(g.k, frame, 4, seed) * 0.12f * amt else 1f) * g.pop
        val cx = g.x + charW(g.c) / 2f
        val cy = g.y + 3.5f
        val pen = DangoClient.gfx.text
        pen.push()
        pen.translate(cx + dx, cy + dy)
        pen.rotate(rot)
        pen.scale(sc, sc)
        pen.translate(-cx, -cy)
        draw()
        pen.pop()
    }

    /** 画一个字：y 是字的顶部（和 MC 字体一样） */
    private fun drawGlyph(c: Char, x: Float, y: Float, rgb: Int, a: Float, smooth: Boolean, shadow: Boolean) {
        val pen = DangoClient.gfx.text
        if (smooth) {
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            val baseline = y + 7.6f
            if (shadow) {
                pen.color(0f, 0f, 0f, 0.45f * a)
                pen.smoothText(c.toString(), x + 0.6f, baseline + 0.6f, lyricSize, CjkStyle.PLAIN)
            }
            pen.color(r, g, b, a)
            pen.smoothText(c.toString(), x, baseline, lyricSize, CjkStyle.PLAIN)
        } else {
            val alpha = (a * 255f).toInt()
            if (alpha < 5) return                          // FontRenderer 会把 alpha<4 当成不透明
            pen.pixelText(c.toString(), x, y, (alpha shl 24) or rgb, shadow)
        }
    }

    /**
     * 逐字出现：每个字按顺序在 revealMs 内依次淡入并从下往上浮起一点；
     * 发光由 [DeepGlow] 另外做（先把字画到发光画布上），这里只画字本身。
     */
    private fun drawAnimated(lines: List<String>, elapsedMs: Long, revealMs: Long, alphaMul: Float, charTimes: LongArray? = null) {
        val smooth = smoothFont.get()
        val flashMode = charTimes != null && charTimes.isNotEmpty()
        var n = 0   // 非空白字符序号
        val total = lines.sumOf { it.length }.coerceAtLeast(1)
        val lineH = 11f
        var y = -(lines.size * lineH) / 2f
        var k = 0
        val glyphs = ArrayList<Glyph>()
        for (line in lines) {
            var x = -strW(line) / 2f
            for (c in line) {
                if (flashMode && !c.isWhitespace()) {
                    // 按词闪出：到了这个词的时间，整个词一下子出现，先放大再弹回原大小
                    val at = charTimes!![min(n, charTimes.size - 1)]
                    n++
                    val t = (elapsedMs - at) / 160f
                    if (t >= 0f) {
                        val a = (t * 3f).coerceIn(0f, 1f) * alphaMul
                        val back = (1f - t).coerceIn(0f, 1f)
                        val pop = 1f + 0.55f * back * back
                        if (a > 0.02f) glyphs += Glyph(c, x, y, a, k, pop)
                    }
                } else if (!flashMode) {
                    val appearAt = k.toFloat() / total * revealMs
                    var a = ((elapsedMs - appearAt) / 180f).coerceIn(0f, 1f)
                    a = a * a * (3f - 2f * a)                 // smoothstep
                    val alpha = a * alphaMul
                    if (alpha > 0.02f && !c.isWhitespace()) glyphs += Glyph(c, x, y + (1f - a) * 5f, alpha, k)
                }
                x += charW(c)
                k++
            }
            y += lineH
        }
        if (glyphs.isEmpty()) return

        if (smooth) DangoClient.gfx.text.beginSmooth()

        val seed = lines.hashCode()
        val frame = System.currentTimeMillis() * wiggleFps.value.coerceAtLeast(1) / 1000L
        for (g in glyphs) withWiggle(g, seed, frame) { drawGlyph(g.c, g.x, g.y, textRgb, g.a, smooth, !glowPass) }

        if (smooth) DangoClient.gfx.text.endSmooth()
    }

    // ---------------------------------------------------------------- 不跑出屏幕

    private var keepNanos = 0L

    /**
     * "Above ground" 的最低高度，平滑地跟着镜头走：
     * 用每帧插值过的镜头位置（不是一秒 20 次跳变的玩家位置），往上慢慢跟、往下快一点跟，
     * 跳起来时字只会被柔和地托起一点，不会一顿一顿地抽。
     */
    private var groundFloor = Double.NaN

    private fun wrapDegrees(d: Double): Double {
        var v = d % 360.0
        if (v >= 180.0) v -= 360.0
        if (v < -180.0) v += 360.0
        return v
    }

    /**
     * "Stay on screen"：歌词（连同字的宽度）超出视野时，沿着离视线最近的方向滑回屏幕边上。
     * 用视线方向的偏角（左右 / 上下）判断，视野大小按设置里的 FOV 和窗口宽高比算；离镜头的距离不变。
     * 每帧在画之前做一次，发光层和字本身用的是同一个位置。
     *
     * 往回拉用的是"临界阻尼弹簧"：有速度、会慢慢加速和减速，不会一碰到边就突然开始动、一回来就突然停，
     * 跳跃 / 飞行这种上下来回的时候也是顺的。"Above ground" 的最低高度也算成一个边界交给同一个弹簧。
     */
    private fun keepOnScreen() {
        val now = System.nanoTime()
        val dt = if (keepNanos == 0L) 0.016 else ((now - keepNanos) / 1e9).coerceIn(0.0, 0.1)
        keepNanos = now
        if (!stayOnScreen.get()) return
        val platform = DangoClient.platform
        val player = platform.player ?: return
        val world = DangoClient.gfx.world

        if (aboveGround.get()) {
            val target = world.cameraY - GROUND_MARGIN
            groundFloor = if (groundFloor.isNaN()) target else {
                val tau = if (target > groundFloor) 0.3 else 0.15
                groundFloor + (target - groundFloor) * (1.0 - exp(-dt / tau))
            }
        } else {
            groundFloor = Double.NaN
        }

        val vHalf = platform.fov / 2.0
        val aspect = platform.windowWidth.toDouble() / platform.windowHeight.coerceAtLeast(1)
        val hHalf = Math.toDegrees(atan(tan(Math.toRadians(vHalf)) * aspect))
        val s = 0.03 * scale.value * (distance.value / 6.5)

        fun follow(f: Floating) {
            val rx = f.x - world.cameraX
            val ry = f.y - world.cameraY
            val rz = f.z - world.cameraZ
            val len = sqrt(rx * rx + ry * ry + rz * rz)
            if (len < 1e-4) return

            // 字本身占的角度：留出一半宽 / 高，再多留 2 度边距
            val halfW = Math.toDegrees(atan2(f.lines.maxOf { strW(it) } * s / 2.0, len))
            val halfH = Math.toDegrees(atan2(f.lines.size * 11.0 * s / 2.0, len))
            val hLim = max(0.0, hHalf - halfW - 2.0)
            val vLim = max(0.0, vHalf - halfH - 2.0)

            val curYaw = Math.toDegrees(atan2(-rx, rz))
            val curPitch = Math.toDegrees(asin((-ry / len).coerceIn(-1.0, 1.0)))
            val dYaw = wrapDegrees(curYaw - player.yaw)
            val dPitch = curPitch - player.pitch

            // 往下最多到哪：屏幕下边，以及（Above ground）不低于地面高度，哪个更靠上用哪个
            var lowLim = vLim
            if (!groundFloor.isNaN()) {
                val floorPitch = Math.toDegrees(asin(((world.cameraY - groundFloor) / len).coerceIn(-1.0, 1.0)))
                lowLim = min(lowLim, floorPitch - player.pitch)
            }
            val tYaw = dYaw.coerceIn(-hLim, hLim)
            val tPitch = if (lowLim >= -vLim) dPitch.coerceIn(-vLim, lowLim) else lowLim

            // 偏出去多少（在边界以内就是 0）
            val eYaw = dYaw - tYaw
            val ePitch = dPitch - tPitch
            if (eYaw == 0.0 && ePitch == 0.0 && abs(f.vYaw) < 0.05 && abs(f.vPitch) < 0.05) {
                f.vYaw = 0.0
                f.vPitch = 0.0
                return
            }

            // 临界阻尼弹簧，分成小步算（帧率低时也稳定）
            var moveYaw = 0.0
            var movePitch = 0.0
            var left = dt
            while (left > 1e-6) {
                val h = min(left, 1.0 / 240.0)
                left -= h
                f.vYaw += (-SPRING * SPRING * (eYaw - moveYaw) - 2.0 * SPRING * f.vYaw) * h
                f.vPitch += (-SPRING * SPRING * (ePitch - movePitch) - 2.0 * SPRING * f.vPitch) * h
                moveYaw += f.vYaw * h
                movePitch += f.vPitch * h
            }

            val newYaw = curYaw + moveYaw
            val newPitch = (curPitch + movePitch).coerceIn(-89.0, 89.0)
            val yr = Math.toRadians(newYaw)
            val pr = Math.toRadians(newPitch)
            f.x = world.cameraX - sin(yr) * cos(pr) * len
            f.y = world.cameraY - sin(pr) * len
            f.z = world.cameraZ + cos(yr) * cos(pr) * len
            // 不朝向镜头时，字的朝向跟着一起转，免得斜着看
            f.yaw = (f.yaw + wrapDegrees(newYaw - curYaw)).toFloat()
            f.pitch = (f.pitch + (newPitch - curPitch)).toFloat().coerceIn(-90f, 90f)
        }

        active?.let { follow(it) }
        fading.forEach { follow(it) }
    }

    private fun drawWorld(partialTicks: Float, songNow: Long, lyrics: Lyrics?, beat: Float) {
        val world = DangoClient.gfx.world
        world.begin()

        fun draw(f: Floating, alphaMul: Float, lift: Float) {
            var rx = f.x - world.cameraX
            var ry = f.y - world.cameraY
            var rz = f.z - world.cameraZ
            var len = sqrt(rx * rx + ry * ry + rz * rz)

            // 离得太近（走过去 / 疾跑）就沿视线方向把歌词往后推，保持最小距离
            val minD = distance.value * 0.6
            if (keepAway.get() && len in 1e-4..minD) {
                val k = minD / len
                rx *= k; ry *= k; rz *= k
                f.x = world.cameraX + rx
                f.y = world.cameraY + ry
                f.z = world.cameraZ + rz
                len = minD
            }

            val yaw: Float
            val pitch: Float
            if (facePlayer.get() && len > 1e-4) {
                // 朝向镜头：根据镜头 → 歌词的方向算出转角
                yaw = Math.toDegrees(atan2(-rx, rz)).toFloat()
                pitch = Math.toDegrees(atan2(-ry, sqrt(rx * rx + rz * rz))).toFloat()
            } else {
                yaw = f.yaw
                pitch = f.pitch
            }
            val s = 0.03f * scale.value * (distance.value / 6.5f)
            world.push(rx, ry + lift, rz, yaw, pitch, s)
            drawAnimated(f.lines, songNow - f.startMs, f.revealMs, alphaMul, if (wordPop.get()) f.charTimes else null)
            world.pop()
        }

        drawFading { f, a, lift -> draw(f, a, lift) }
        active?.let { draw(it, appearOf(it), 0f) }
    }

    private fun drawFading(draw: (Floating, Float, Float) -> Unit) {
        val now = System.nanoTime()
        val it = fading.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val t = (now - f.fadeStartNanos) / 1e9f
            val a = 1f - t / 0.4f
            if (a <= 0f) {
                it.remove()
                continue
            }
            draw(f, a, t * 0.4f)
        }
    }

    /**
     * 左上角：
     *   [封面]  ♪ 歌名
     *           歌手
     *           [时钟来源 进度]  歌词来源
     */
    /** 歌曲卡片 HUD 正在显示内置播放器的歌时，就不再画左上角的信息，免得重复 */
    private fun songCardCovers(): Boolean = try {
        DangoMusic.instance.moduleManager.get(SongCardModule::class.java).showing
    } catch (_: Exception) {
        false
    }

    private fun drawNowPlaying(s: NowPlaying, lyrics: Lyrics?) {
        val hud = DangoClient.gfx.hud
        hud.begin()
        val x = 6
        val y = 6
        val coverSize = 32

        // 封面（换歌时淡入）；还没加载到时画一个占位方块
        var textX = x
        if (showCover.get()) {
            val loc = cover.location
            val ready = loc != null && cover.shownKey == s.key
            hud.rect(x - 1, y - 1, x + coverSize + 1, y + coverSize + 1, 0x66000000)
            if (ready) {
                val fade = ((System.nanoTime() - cover.shownAtNanos) / 1e9f / 0.35f).coerceIn(0f, 1f)
                hud.texture(loc!!, x, y, coverSize, coverSize, fade)
            } else {
                hud.rect(x, y, x + coverSize, y + coverSize, 0x55F2A7C3)
            }
            textX = x + coverSize + 6
        }

        // 三根跳动的小柱子：颜色跟随封面主色，换歌时慢慢过渡
        val target = if (cover.shownKey == s.key) cover.accent else CoverArt.DEFAULT_ACCENT
        val nowNs = System.nanoTime()
        val dt = if (barColorNanos == 0L) 1f else ((nowNs - barColorNanos) / 1e9f).coerceIn(0f, 0.1f)
        barColorNanos = nowNs
        val k = 1f - kotlin.math.exp(-dt / 0.25f)
        barR += (((target shr 16) and 0xFF) / 255f - barR) * k
        barG += (((target shr 8) and 0xFF) / 255f - barG) * k
        barB += ((target and 0xFF) / 255f - barB) * k
        val barColor = (0xFF shl 24) or ((barR * 255).toInt().coerceIn(0, 255) shl 16) or
            ((barG * 255).toInt().coerceIn(0, 255) shl 8) or (barB * 255).toInt().coerceIn(0, 255)
        val t = nowNs / 1e9
        for (i in 0 until 3) {
            val h = if (s.playing) (3 + 4 * (0.5 + 0.5 * sin(t * (6.0 + i * 1.7) + i))).toInt() else 2
            val bx = textX + i * 3
            hud.rect(bx, y + 8 - h, bx + 2, y + 8, barColor)
        }

        val title = s.title.let { if (it.length > 40) it.take(39) + "…" else it }
        hud.text(title, (textX + 12).toFloat(), y.toFloat(), 0xFFFFFFFF.toInt(), true)
        if (s.artist.isNotEmpty()) {
            hud.text(s.artist, textX.toFloat(), (y + 11).toFloat(), 0xFFB8B8C8.toInt(), true)
        }

        hud.end()
    }
}
