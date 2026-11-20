package me.sorihiko.dango.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 生物跟着歌曲的节奏摇：从播放器检测到的鼓点估出 BPM 和拍子网格，
 * 算出这一帧所有生物共用的姿势（踩点压扁再拉长、匀速转圈）。各版本画生物时套上这个姿势。
 *
 * 大部分歌 BPM 不变，所以每首歌只估一次：攒够 [FIRST_LOCK_MS] 的鼓点先定下来，
 * 到 [FINAL_LOCK_MS] 用更多鼓点再算准一次，之后整首歌都用这个网格，不再跟着鼓点修。
 * 第 n 拍在歌曲内时间 gridMs + n * periodMs，所以拖进度条也直接对得上。
 *
 * 也可以自己对拍：开了 [tap] 后，玩家跟着节拍左键点 [TAP_COUNT] 下（第一下开始算，停 2 秒没点就重来），
 * 用点的时间定这首歌的 BPM 和网格，比自动估的优先。换歌后要重新点。
 */
object Dance {
    /** 设置：开关、转圈、幅度（1 = 默认） */
    @Volatile var enabled = false
    @Volatile var spin = false
    @Volatile var amount = 1f
    /** 转圈速度倍数：1 = 四拍转一圈，2 = 两拍一圈，0.5 = 八拍一圈 */
    @Volatile var spinSpeed = 1f
    /** 踩点偏移（ms，正 = 往后推），各人声卡延迟不一样，用来对准 */
    @Volatile var offsetMs = 0
    /** 自动从鼓点估 BPM */
    @Volatile var auto = true
    /** 左键点 [TAP_COUNT] 下自己对拍 */
    @Volatile var tap = false

    /** 一拍的长度（ms），0 = 还没估出来 */
    @Volatile var periodMs = 0.0
        private set
    /** 拍子网格的起点（歌曲内 ms，0..periodMs） */
    private var gridMs = 0.0
    /** 0 = 还没定，1 = 先定了，2 = 定准了 */
    private var lockLevel = 0

    /** 这首歌攒下来的鼓点：时间（ms）和强度 */
    private val times = ArrayList<Double>()
    private val strengths = ArrayList<Double>()
    private var song: NeteaseTrack? = null
    private var lastCollect = 0L

    /** 正在点的这一轮：每一下的歌曲时间（ms） */
    private val taps = ArrayList<Double>()
    private var lastTapAt = 0L
    private var wasDown = false
    /** 这首歌已经点出来了（不再用自动估的） */
    private var tapped = false
    /** 点完的时间（显示一会儿结果） */
    private var tapDoneAt = 0L

    /** 平滑后的歌曲时间（声卡报的位置是一截一截跳的） */
    private var songClock = 0.0
    private var clockValid = false

    /** 摇的幅度，开始 / 停止时慢慢过渡 */
    private var envelope = 0f
    private var lastFrame = 0L
    private var cachedFrame = -1L
    private var cached: FloatArray? = null
    /** 转圈转到的角度 */
    private var spinAngle = 0.0

    private fun reset() {
        times.clear()
        strengths.clear()
        periodMs = 0.0
        gridMs = 0.0
        lockLevel = 0
        clockValid = false
        taps.clear()
        tapped = false
    }

    /** 换歌了就把这首歌攒的东西全清掉 */
    private fun checkSong() {
        val cur = MusicPlayer.current
        if (cur !== song) {
            song = cur
            reset()
        }
    }

    /**
     * 每帧调一次：[mouseDown] = 左键现在按着，[inGame] = 在游戏画面里（没开界面）。
     * 按下的那一刻算一下。
     */
    fun tapTick(mouseDown: Boolean, inGame: Boolean) {
        val pressed = mouseDown && !wasDown
        wasDown = mouseDown
        if (!enabled || !tap || !inGame || !pressed) return
        if (MusicPlayer.state != MusicPlayer.State.PLAYING) return
        checkSong()
        val now = System.currentTimeMillis()
        if (now - lastTapAt > 2000L) taps.clear()
        lastTapAt = now
        taps += MusicPlayer.positionMs.toDouble()
        if (taps.size < TAP_COUNT) return
        // 点的时间对第几下做直线拟合：斜率 = 一拍多长，截距 = 网格起点
        val n = taps.size
        val meanI = (n - 1) / 2.0
        val meanT = taps.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (i - meanI) * (taps[i] - meanT)
            den += (i - meanI) * (i - meanI)
        }
        val period = num / den
        taps.clear()
        if (period < 250.0 || period > 1500.0) return
        // 点下去的那一刻就是压到最扁的那一刻（画面会按 LATENCY_MS 推，这里先抵掉）
        val start = meanT - meanI * period + LATENCY_MS
        periodMs = period
        gridMs = ((start % period) + period) % period
        lockLevel = 2
        tapped = true
        clockValid = false
        tapDoneAt = now
    }

    /** 对拍的提示：正在点时是 "5 / 16"，刚点完两秒内是 "BPM 128"，别的时候 null */
    fun tapStatus(): String? {
        if (!enabled || !tap) return null
        val now = System.currentTimeMillis()
        if (taps.isNotEmpty() && now - lastTapAt <= 2000L) return "${taps.size} / $TAP_COUNT"
        if (tapped && now - tapDoneAt <= 2000L) return "BPM $bpm"
        return null
    }

    /** 把播放器新检测到的鼓点收进来，攒够了就定网格（半秒一次就够了） */
    private fun collect(now: Long) {
        checkSong()
        if (!auto || lockLevel == 2 || now - lastCollect < 500L) return
        lastCollect = now
        val last = times.lastOrNull() ?: -1.0
        for (b in MusicPlayer.beatSnapshot()) {
            val t = b[0].toDouble()
            if (t <= last) continue
            times += t
            strengths += b[1].toDouble()
        }
        if (times.size < 8) return
        val span = times.last() - times.first()
        if (lockLevel == 0 && span >= FIRST_LOCK_MS) fit()?.let { lock(it, 1) }
        else if (lockLevel == 1 && span >= FINAL_LOCK_MS) fit()?.let { lock(it, 2) }
    }

    private fun lock(fit: DoubleArray, level: Int) {
        periodMs = fit[0]
        gridMs = fit[1]
        lockLevel = level
    }

    /**
     * 用攒下来的鼓点估 BPM 和网格：先用相邻间隔（折算到 60～200 BPM）的中位数粗估，
     * 再在附近细找让所有鼓点最整齐地落在网格上的拍长（强的鼓点权重大）。返回 [拍长, 网格起点]。
     */
    private fun fit(): DoubleArray? {
        val intervals = ArrayList<Double>()
        for (i in 1 until times.size) {
            var d = times[i] - times[i - 1]
            if (d <= 0.0) continue
            while (d > 1000.0) d /= 2.0
            while (d < 300.0) d *= 2.0
            intervals += d
        }
        if (intervals.size < 6) return null
        intervals.sort()
        val rough = intervals[intervals.size / 2]
        var best = rough
        var bestScore = -1.0
        var p = rough * 0.97
        while (p <= rough * 1.03) {
            val score = hypot(alignment(p)[0], alignment(p)[1])
            if (score > bestScore) {
                bestScore = score
                best = p
            }
            p += 0.05
        }
        val a = alignment(best)
        return doubleArrayOf(best, ((atan2(a[1], a[0]) / (2.0 * PI) * best) + best) % best)
    }

    /** 拍长为 [period] 时每个鼓点在一拍里的位置，加权（强度平方）圆周求和；长度越大越整齐 */
    private fun alignment(period: Double): DoubleArray {
        var sx = 0.0
        var sy = 0.0
        for (i in times.indices) {
            val w = strengths[i] * strengths[i]
            val a = 2.0 * PI * (times[i] % period) / period
            sx += cos(a) * w
            sy += sin(a) * w
        }
        return doubleArrayOf(sx, sy)
    }

    /**
     * 这一帧的姿势：[0] 转圈的角度（度），[1] 上下的缩放（1 = 原样，小于 1 = 压扁，大于 1 = 拉长；左右按体积不变反过来缩放）。
     * 不摇的时候返回 null。同一帧里多次调用返回同一个结果。
     */
    fun pose(): FloatArray? {
        val now = System.currentTimeMillis()
        if (now == cachedFrame) return cached
        cachedFrame = now
        cached = compute(now)
        return cached
    }

    private fun compute(now: Long): FloatArray? {
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now
        if (!enabled) {
            envelope = 0f
            spinAngle = 0.0
            return null
        }
        collect(now)
        if (!auto && !tapped && periodMs > 0.0) {
            periodMs = 0.0
            lockLevel = 0
        }
        val playing = MusicPlayer.state == MusicPlayer.State.PLAYING && periodMs > 0.0
        // 开始放 / 暂停时 0.3 秒左右渐变，不会一下子开始或停住
        envelope += ((if (playing) 1f else 0f) - envelope) * (1f - exp(-dt / 0.25f))
        if (!playing) clockValid = false
        if (envelope < 0.01f || periodMs <= 0.0) {
            spinAngle = 0.0
            return null
        }

        // 歌曲时间按真实时间匀速走，只把声卡位置的小跳动抹平；差太多（拖进度、卡顿）直接对上
        val pos = MusicPlayer.positionMs.toDouble()
        if (!clockValid || abs(pos - songClock) > 200.0) {
            songClock = pos
            clockValid = true
        } else {
            songClock += dt * 1000.0
            songClock += ((pos - songClock) * min(1.0, dt * 4.0)).coerceAtLeast(-dt * 500.0)
        }
        val beats = (songClock + LATENCY_MS + offsetMs - gridMs) / periodMs
        val phase = (beats - floor(beats)).toFloat()                  // 0..1：这一拍走到哪
        val squash = 1f + 0.3f * amount * envelope * bounce(phase)
        // 转圈：匀速转，四拍一圈
        if (spin) spinAngle = (spinAngle + dt * 1000.0 / periodMs * 90.0 * spinSpeed.coerceIn(0.1f, 8f) * envelope) % 360.0
        else spinAngle = 0.0
        return floatArrayOf(spinAngle.toFloat(), squash)
    }

    /**
     * 一拍里的形变（-1 = 压扁到底，正 = 拉长）：拍子前一点点开始往下压，正好在拍子上压到最扁，
     * 然后弹起来拉长，拍子一半的时候回到原样，后半拍不动，等下一拍。
     */
    private fun bounce(phase: Float): Float {
        val p = if (phase > 0.92f) phase - 1f else phase          // -0.08..0.92，0 = 拍子
        fun ease(t: Float) = t * t * (3f - 2f * t)
        return when {
            p < 0f -> -ease((p + 0.08f) / 0.08f)                        // 往下压
            p < 0.22f -> -1f + 1.6f * ease(p / 0.22f)                   // 弹起来拉长
            p < 0.5f -> 0.6f * (1f - ease((p - 0.22f) / 0.28f))         // 回到原样
            else -> 0f
        }
    }

    /** 对拍要点几下 */
    const val TAP_COUNT = 16

    /** 攒够这么长的鼓点先定网格（ms） */
    private const val FIRST_LOCK_MS = 8000.0

    /** 攒够这么长的鼓点再算准一次，之后不再动（ms） */
    private const val FINAL_LOCK_MS = 30000.0

    /** 声卡输出比报的播放位置晚一点，画面往后推这么多（ms） */
    private const val LATENCY_MS = -60.0

    /** 现在估出来的 BPM（界面显示用），没估出来是 0 */
    val bpm: Int get() = if (periodMs > 0.0) (60000.0 / periodMs + 0.5).toInt() else 0
}
