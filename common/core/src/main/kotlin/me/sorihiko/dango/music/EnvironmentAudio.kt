package me.sorihiko.dango.music

import me.sorihiko.dango.platform.ListenerEnvironment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * 环境音效：歌曲跟着玩家所在的地方变化，在解码线程里、写给声卡之前处理。
 *   房间回声：按 [RoomAcoustics] 测出来的空间（多大、多封闭、墙是什么材质）实时调混响，
 *            小屋、大厅、石头洞穴、羊毛房听起来都不一样；软的材质还会让声音发闷
 *   水下：滤掉高频，像隔着水听
 *   下雨 / 打雷（可单独关）：音量稍微压低，让雨声透出来
 *
 * 游戏线程每 tick 调 [update] 算出目标值；解码线程每帧调 [process]，参数朝目标慢慢过渡（约半秒），不会突变出爆音。
 * 全都不起作用时直接跳过，不占性能。
 */
object EnvironmentAudio {
    // 后期混响的梳状滤波器和全通滤波器长度（44.1 kHz 下的采样数，其他采样率按比例换算）。
    // 右声道每个都长一点点，左右回声不完全一样，听起来更宽
    private val COMB_SAMPLES = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491)
    private val ALLPASS_SAMPLES = intArrayOf(556, 441)
    private const val STEREO_SPREAD = 23
    // 早期反射：第一次反射之后陆续回来的几次，时间是第一次的倍数，越晚越弱，左右交替
    private val TAP_TIMES = floatArrayOf(1f, 1.37f, 1.71f, 2.13f, 2.6f, 3.1f)
    private val TAP_GAINS = floatArrayOf(0.8f, 0.62f, 0.5f, 0.4f, 0.31f, 0.24f)

    /** 设置（由 Music 模块每 tick 同步过来） */
    @Volatile var enabled = false
    @Volatile var rain = true
    @Volatile var strength = 1f

    @Volatile private var targetCutoff = OPEN_CUTOFF
    @Volatile private var targetWet = 0f
    @Volatile private var targetDecay = 0.5f
    @Volatile private var targetEarly = 10f
    @Volatile private var targetDamping = 0.3f
    @Volatile private var targetPan = 0f
    @Volatile private var targetGain = 1f

    /** 游戏线程：根据玩家周围的环境和测出来的空间算出要过渡到的效果 */
    fun update(env: ListenerEnvironment?, room: RoomAcoustics.Room?) {
        if (!enabled || env == null) {
            targetCutoff = OPEN_CUTOFF
            targetWet = 0f
            targetGain = 1f
            return
        }
        val s = strength.coerceIn(0f, 1f)
        var cutoff = OPEN_CUTOFF
        var wet = 0f
        var gain = 1f
        if (room != null) {
            val enclosed = room.enclosure
            // 越封闭、混响越长，回声越明显；空旷的地方基本是干声
            wet = MAX_WET * enclosed.pow(1.5f) * (room.decaySeconds / 0.5f).coerceAtMost(1f)
            // 封闭的地方变闷：硬墙只闷一点（主要靠回声听出空间），软墙（羊毛、雪）把高音吸掉，像被棉花包住，音量也小一点
            val closed = enclosed * enclosed
            val soft = ((room.absorption - 0.1f) / 0.5f).coerceIn(0f, 1f)
            val hardCutoff = exp(ln(OPEN_CUTOFF) + (ln(HARD_ROOM_CUTOFF) - ln(OPEN_CUTOFF)) * closed)
            val softCutoff = exp(ln(OPEN_CUTOFF) + (ln(SOFT_ROOM_CUTOFF) - ln(OPEN_CUTOFF)) * closed)
            cutoff = exp(ln(hardCutoff) + (ln(softCutoff) - ln(hardCutoff)) * soft)
            gain *= 1f - 0.2f * closed * soft
            targetDecay = room.decaySeconds
            targetEarly = room.earlyDelayMs
            // 软的材质吸高音多：回声尾巴更暗
            targetDamping = (0.15f + room.absorption * 1.5f).coerceIn(0.1f, 0.85f)
            targetPan = room.pan * enclosed
        }
        if (env.underwater) {
            cutoff = minOf(cutoff, WATER_CUTOFF)
            gain *= 0.85f
            targetDamping = 0.85f
        }
        if (rain) gain *= 1f - 0.2f * env.rain
        // 强度：在"原样"和"满效果"之间插（截止频率按对数插，听起来才均匀）
        targetCutoff = exp(ln(OPEN_CUTOFF) + (ln(cutoff) - ln(OPEN_CUTOFF)) * s)
        targetWet = wet * s
        targetGain = 1f - (1f - gain) * s
    }

    // ------------------------------------------------------------ 解码线程

    private var cutoff = OPEN_CUTOFF
    private var wet = 0f
    private var decay = 0.5f
    private var early = 10f
    private var damping = 0.3f
    private var pan = 0f
    private var gain = 1f

    private var rate = 0
    private var coefCutoff = -1f
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private val x1 = FloatArray(2); private val x2 = FloatArray(2); private val y1 = FloatArray(2); private val y2 = FloatArray(2)

    /** 早期反射用的延迟线（单声道输入） */
    private var delay = FloatArray(1)
    private var delayPos = 0
    /** [声道][第几个] */
    private var combs: Array<Array<FloatArray>> = emptyArray()
    private val combPos = Array(2) { IntArray(COMB_SAMPLES.size) }
    private val combStore = Array(2) { FloatArray(COMB_SAMPLES.size) }
    private val combFeedback = Array(2) { FloatArray(COMB_SAMPLES.size) }
    /** 混响越长，梳状滤波器里攒的能量越多：按反馈把输入调小，让不同房间的回声音量差不多，只是长短不同 */
    private var lateLevel = 0.1f
    private var allpasses: Array<Array<FloatArray>> = emptyArray()
    private val allpassPos = Array(2) { IntArray(ALLPASS_SAMPLES.size) }

    /** 新的一首 / 拖了进度：清掉滤波器和混响里残留的声音 */
    fun reset() {
        x1.fill(0f); x2.fill(0f); y1.fill(0f); y2.fill(0f)
        delay.fill(0f)
        for (side in combs) for (c in side) c.fill(0f)
        for (side in allpasses) for (a in side) a.fill(0f)
        for (s in combStore) s.fill(0f)
        cutoff = targetCutoff
        wet = targetWet
        decay = targetDecay
        early = targetEarly
        damping = targetDamping
        pan = targetPan
        gain = targetGain
        coefCutoff = -1f
    }

    /** 处理一帧（交错存放的 16 位样本，[length] 是样本总数），原地修改 */
    fun process(pcm: ShortArray, length: Int, channels: Int, sampleRate: Int, frameMs: Float) {
        val k = 1f - exp(-frameMs / SMOOTH_MS)
        cutoff = exp(ln(cutoff) + (ln(targetCutoff) - ln(cutoff)) * k)
        wet += (targetWet - wet) * k
        decay += (targetDecay - decay) * k
        early += (targetEarly - early) * k
        damping += (targetDamping - damping) * k
        pan += (targetPan - pan) * k
        gain += (targetGain - gain) * k
        val filtering = cutoff < OPEN_CUTOFF * 0.97f
        val reverb = wet > 0.002f
        if (!filtering && !reverb && gain > 0.999f) return

        val ch = channels.coerceIn(1, 2)
        if (sampleRate != rate) setup(sampleRate)
        if (filtering && cutoff != coefCutoff) lowPass(cutoff)
        if (reverb) updateFeedback()
        // 早期反射各次的延迟（采样数）和左右分配
        val baseDelay = early / 1000f * rate
        val tapLeft = FloatArray(TAP_TIMES.size)
        val tapRight = FloatArray(TAP_TIMES.size)
        for (t in TAP_TIMES.indices) {
            // 交替偏左偏右，再整体往墙近的一边偏
            val side = ((if (t % 2 == 0) -0.6f else 0.6f) + pan * 0.8f).coerceIn(-1f, 1f)
            tapLeft[t] = TAP_GAINS[t] * (1f - side) * 0.5f
            tapRight[t] = TAP_GAINS[t] * (1f + side) * 0.5f
        }
        val lateDelay = (baseDelay * 1.5f).toInt()
        val out = FloatArray(2)
        var i = 0
        while (i + ch - 1 < length) {
            var mono = 0f
            for (c in 0 until ch) {
                var v = pcm[i + c].toFloat()
                if (filtering) {
                    val y = b0 * v + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
                    x2[c] = x1[c]; x1[c] = v
                    y2[c] = y1[c]; y1[c] = y
                    v = y
                }
                pcm[i + c] = v.toInt().coerceIn(-32768, 32767).toShort()
                mono += v
            }
            if (reverb) {
                reverbSample(mono / ch, baseDelay, tapLeft, tapRight, lateDelay, out)
                if (ch == 2) {
                    pcm[i] = ((pcm[i] + out[0] * wet) * gain).toInt().coerceIn(-32768, 32767).toShort()
                    pcm[i + 1] = ((pcm[i + 1] + out[1] * wet) * gain).toInt().coerceIn(-32768, 32767).toShort()
                } else {
                    pcm[i] = ((pcm[i] + (out[0] + out[1]) * 0.5f * wet) * gain).toInt().coerceIn(-32768, 32767).toShort()
                }
            } else if (gain < 0.999f) {
                for (c in 0 until ch) pcm[i + c] = (pcm[i + c] * gain).toInt().coerceIn(-32768, 32767).toShort()
            }
            i += ch
        }
    }

    private fun setup(sampleRate: Int) {
        rate = sampleRate
        val scale = sampleRate / 44100f
        // 延迟线要装得下最晚的一次早期反射加后期混响的预延迟
        delay = FloatArray((MAX_EARLY_MS / 1000f * sampleRate * (TAP_TIMES.last() + 0.5f)).toInt() + 2)
        delayPos = 0
        combs = Array(2) { side -> Array(COMB_SAMPLES.size) { FloatArray(((COMB_SAMPLES[it] + side * STEREO_SPREAD) * scale).toInt().coerceAtLeast(1)) } }
        allpasses = Array(2) { side -> Array(ALLPASS_SAMPLES.size) { FloatArray(((ALLPASS_SAMPLES[it] + side * STEREO_SPREAD) * scale).toInt().coerceAtLeast(1)) } }
        for (p in combPos) p.fill(0)
        for (p in allpassPos) p.fill(0)
        reset()
    }

    /** 每个梳状滤波器的反馈：声音每绕一圈衰减多少，正好让整体在 [decay] 秒内衰减 60 dB */
    private fun updateFeedback() {
        var energy = 0f
        for (side in 0..1) for (j in COMB_SAMPLES.indices) {
            val seconds = combs[side][j].size.toFloat() / rate
            val g = 10f.pow(-3f * seconds / decay).coerceAtMost(0.97f)
            combFeedback[side][j] = g
            energy += 1f / (1f - g * g)
        }
        lateLevel = LATE_INPUT / kotlin.math.sqrt(energy / (2 * COMB_SAMPLES.size) / 2.8f)
    }

    /** 二阶低通（RBJ 公式，Q = 0.707） */
    private fun lowPass(fc: Float) {
        coefCutoff = fc
        val w0 = 2.0 * PI * fc.coerceAtMost(rate * 0.45f) / rate
        val alpha = sin(w0) / (2.0 * 0.7071)
        val cw = cos(w0)
        val a0 = 1.0 + alpha
        b0 = ((1.0 - cw) / 2.0 / a0).toFloat()
        b1 = ((1.0 - cw) / a0).toFloat()
        b2 = b0
        a1 = (-2.0 * cw / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    /** 一个采样的回声：早期反射（几次清楚的回声）+ 后期混响（并联梳状滤波器，再串全通滤波器把回声打散） */
    private fun reverbSample(input: Float, baseDelay: Float, tapLeft: FloatArray, tapRight: FloatArray, lateDelay: Int, out: FloatArray) {
        val size = delay.size
        delay[delayPos] = input
        var earlyL = 0f
        var earlyR = 0f
        for (t in TAP_TIMES.indices) {
            val v = read(baseDelay * TAP_TIMES[t], size)
            earlyL += v * tapLeft[t]
            earlyR += v * tapRight[t]
        }
        val lateInput = delay[Math.floorMod(delayPos - lateDelay.coerceIn(0, size - 1), size)] * lateLevel
        delayPos = if (delayPos + 1 == size) 0 else delayPos + 1

        for (side in 0..1) {
            var late = 0f
            val sideCombs = combs[side]
            for (j in sideCombs.indices) {
                val buf = sideCombs[j]
                val p = combPos[side][j]
                val y = buf[p]
                combStore[side][j] = y * (1f - damping) + combStore[side][j] * damping
                buf[p] = lateInput + combStore[side][j] * combFeedback[side][j]
                combPos[side][j] = if (p + 1 == buf.size) 0 else p + 1
                late += y
            }
            for (j in allpasses[side].indices) {
                val buf = allpasses[side][j]
                val p = allpassPos[side][j]
                val delayed = buf[p]
                buf[p] = late + delayed * ALLPASS_GAIN
                late = delayed - late * ALLPASS_GAIN
                allpassPos[side][j] = if (p + 1 == buf.size) 0 else p + 1
            }
            out[side] = late + (if (side == 0) earlyL else earlyR) * EARLY_LEVEL
        }
    }

    /** 从延迟线里读 [samplesAgo] 个采样之前的值（线性插值，延迟慢慢变化时不会咔哒） */
    private fun read(samplesAgo: Float, size: Int): Float {
        val d = samplesAgo.coerceIn(0f, (size - 2).toFloat())
        val whole = d.toInt()
        val frac = d - whole
        val a = delay[Math.floorMod(delayPos - whole, size)]
        val b = delay[Math.floorMod(delayPos - whole - 1, size)]
        return a + (b - a) * frac
    }

    /** 不加滤波时的截止频率（等于不处理） */
    private const val OPEN_CUTOFF = 20000f
    private const val WATER_CUTOFF = 450f
    /** 完全封闭的石头房里闷到多少 */
    private const val HARD_ROOM_CUTOFF = 10000f
    /** 完全封闭的羊毛房里闷到多少 */
    private const val SOFT_ROOM_CUTOFF = 2200f
    /** 回声最多占多少（大石头洞穴） */
    private const val MAX_WET = 0.6f
    private const val EARLY_LEVEL = 0.8f
    /** 后期混响的输入音量（一般房间时） */
    private const val LATE_INPUT = 0.25f
    private const val MAX_EARLY_MS = 80f
    private const val SMOOTH_MS = 350f
    private const val ALLPASS_GAIN = 0.5f
}
