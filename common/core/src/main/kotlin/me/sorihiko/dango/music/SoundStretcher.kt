package me.sorihiko.dango.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * 变速 / 变调（自己写的，不依赖别的库）。
 *
 * - 速度 [speed]：时长变成 1 / speed，音高不变。用 WSOLA：一段一段（40ms）从原声里取，
 *   每段在预定位置附近挪一挪，找和上一段接得最顺的地方，再用汉宁窗叠起来。
 * - 音高 [pitch]：频率 × pitch。先多拉伸 / 少拉伸一点，再三次插值重采样回来，时长不变。
 *
 * 输入输出都是交错的 16 位 PCM。一次解码一个实例（换歌、拖进度都新建），参数可以随时改。
 */
class SoundStretcher(private val channels: Int, rate: Int) {

    @Volatile var speed = 1f
    @Volatile var pitch = 1f

    /** 两个都是 1 时直接原样返回，不占 CPU 也不加延迟 */
    val bypass: Boolean get() = abs(speed - 1f) < 0.001f && abs(pitch - 1f) < 0.001f

    // ---------------------------------------------------------------- WSOLA

    private val seq = (rate * 0.040).toInt().coerceAtLeast(64) and 1.inv()   // 每段长度（偶数）
    private val hop = seq / 2                                                // 输出步长：半段，汉宁窗刚好叠成 1
    private val seek = (rate * 0.012).toInt().coerceAtLeast(8)               // 前后最多挪这么多去找接缝
    private val window = FloatArray(seq) { (0.5 - 0.5 * cos(2.0 * PI * it / seq)).toFloat() }

    /** 还没用完的输入（交错）和对应的单声道（找接缝用） */
    private var inBuf = FloatArray(seq * channels * 4)
    private var inMono = FloatArray(seq * 4)
    private var inLen = 0
    /** inBuf[0] 在整条输入里的帧号 */
    private var inBase = 0L
    /** 下一段的预定位置（整条输入里的帧号，带小数） */
    private var readPos = 0.0
    /** 上一段实际取的位置；-1 = 还没取过 */
    private var prevPos = -1L
    /** 上一段后半截（已乘窗），和下一段前半截叠 */
    private val tail = FloatArray(hop * channels)

    // ---------------------------------------------------------------- 重采样

    /** 拉伸完、等着重采样的（交错） */
    private var mid = FloatArray(seq * channels * 4)
    /** mid[0] 是上一帧（插值要往前看一帧），开头先放一帧静音 */
    private var midLen = 1
    /** 下一个输出点在 mid 里的位置（帧，带小数） */
    private var midPos = 1.0

    private var out = FloatArray(seq * channels * 4)
    private var outLen = 0

    /** 处理一块；返回的数组只在下次调用前有效，长度看 [lastLength]（可能是 0：还在攒输入） */
    fun process(pcm: ShortArray, len: Int): ShortArray {
        val sp = speed.coerceIn(0.25f, 4f).toDouble()
        val pr = pitch.coerceIn(0.25f, 4f).toDouble()
        val tempo = sp / pr
        outLen = 0
        if (abs(tempo - 1.0) < 0.001) {
            // 只是重采样（音调跟着速度走），不用拉伸；刚从拉伸切过来的话，先把没用完的输入接上
            if (stretching) endStretch()
            appendMid(pcm, len)
        } else {
            stretching = true
            appendInput(pcm, len)
            stretch(tempo)
        }
        resample(pr)
        val frames = outLen
        if (result.size < frames * channels) result = ShortArray(frames * channels + 1024)
        for (i in 0 until frames * channels) {
            val v = out[i]
            result[i] = (if (v > 32767f) 32767f else if (v < -32768f) -32768f else v).toInt().toShort()
        }
        lastLength = frames * channels
        return result
    }

    private var result = ShortArray(0)
    var lastLength = 0
        private set

    private var stretching = false

    /**
     * 不再拉伸：上一段后半截加上原声的同一截刚好还原成原声（两个窗加起来是 1），
     * 所以从"上一段自然接下去的位置"起把剩下的输入原样接到 mid 后面，再把拉伸的状态清掉
     */
    private fun endStretch() {
        stretching = false
        val from = if (prevPos < 0) 0 else (prevPos + hop - inBase).toInt().coerceIn(0, inLen)
        val frames = inLen - from
        ensureMid(midLen + frames)
        System.arraycopy(inBuf, from * channels, mid, midLen * channels, frames * channels)
        midLen += frames
        inLen = 0
        inBase = 0L
        readPos = 0.0
        prevPos = -1L
        java.util.Arrays.fill(tail, 0f)
    }

    private fun appendInput(pcm: ShortArray, len: Int) {
        val frames = len / channels
        ensureIn(inLen + frames)
        var k = 0
        for (f in 0 until frames) {
            var m = 0f
            val o = (inLen + f) * channels
            for (c in 0 until channels) {
                val v = pcm[k++].toFloat()
                inBuf[o + c] = v
                m += v
            }
            inMono[inLen + f] = m / channels
        }
        inLen += frames
    }

    private fun ensureIn(frames: Int) {
        if (inMono.size >= frames) return
        val n = maxOf(frames, inMono.size * 2)
        inBuf = inBuf.copyOf(n * channels)
        inMono = inMono.copyOf(n)
    }

    private fun stretch(tempo: Double) {
        val ha = hop * tempo
        while (true) {
            val target = readPos.roundToLong()
            // 要的那段（加上前后挪动的余量）还没到齐，等下一块
            if (target + seek + seq > inBase + inLen) break
            val best = if (prevPos < 0) target else bestOffset(target)
            val s = (best - inBase).toInt()
            ensureMid(midLen + hop)
            val o = midLen * channels
            // 第一段前半截原样放（不从 0 淡入），和前面直接放的声音接得上，开头也不会凹一下
            val first = prevPos < 0
            for (i in 0 until hop) {
                val w1 = if (first) 1f else window[i]
                val w2 = window[i + hop]
                for (c in 0 until channels) {
                    val a = (s + i) * channels + c
                    mid[o + i * channels + c] = tail[i * channels + c] + inBuf[a] * w1
                    tail[i * channels + c] = inBuf[a + hop * channels] * w2
                }
            }
            midLen += hop
            prevPos = best
            readPos += ha
            // 前面用不到的扔掉：下一段最早从 readPos - seek 开始，接缝比较用 prevPos + hop 起
            val keep = minOf(prevPos + hop, readPos.toLong() - seek)
            val drop = (keep - inBase).toInt()
            if (drop > seq) dropInput(drop)
        }
    }

    /** 在 target ± seek 里找和"上一段自然接下去的那截"最像的位置（单声道，每 2 个点算一次） */
    private fun bestOffset(target: Long): Long {
        val natural = (prevPos + hop - inBase).toInt()
        val lo = maxOf((target - seek - inBase).toInt(), 0)
        val hi = (target + seek - inBase).toInt()
        if (natural < 0 || natural + hop > inLen) return target
        var best = (target - inBase).toInt().coerceIn(lo, hi)
        var bestScore = Double.NEGATIVE_INFINITY
        var c = lo
        while (c <= hi) {
            var dot = 0.0
            var energy = 1e-9
            var i = 0
            while (i < hop) {
                val b = inMono[c + i]
                dot += inMono[natural + i] * b
                energy += b * b
                i += 2
            }
            val score = dot / sqrt(energy)
            if (score > bestScore) { bestScore = score; best = c }
            c++
        }
        return inBase + best
    }

    private fun dropInput(n: Int) {
        System.arraycopy(inBuf, n * channels, inBuf, 0, (inLen - n) * channels)
        System.arraycopy(inMono, n, inMono, 0, inLen - n)
        inLen -= n
        inBase += n
    }

    private fun appendMid(pcm: ShortArray, len: Int) {
        val frames = len / channels
        ensureMid(midLen + frames)
        val o = midLen * channels
        for (i in 0 until frames * channels) mid[o + i] = pcm[i].toFloat()
        midLen += frames
    }

    private fun ensureMid(frames: Int) {
        if (mid.size >= frames * channels) return
        mid = mid.copyOf(maxOf(frames * channels, mid.size * 2))
    }

    /** 三次（Catmull-Rom）插值，每个输出点在 mid 里往前走 [ratio] 帧 */
    private fun resample(ratio: Double) {
        // 不变调：位置对齐到整帧（最多差半个采样点，听不出来），直接拷贝
        if (abs(ratio - 1.0) < 0.001) midPos = 1.0
        if (midPos == 1.0 && abs(ratio - 1.0) < 0.001) {
            // 不用重采样：留 1 帧在前面，保证之后切到重采样时位置接得上
            val frames = midLen - 1
            if (frames > 0) {
                ensureOut(frames)
                System.arraycopy(mid, channels, out, 0, frames * channels)
                outLen = frames
                System.arraycopy(mid, frames * channels, mid, 0, channels)
                midLen = 1
            }
            return
        }
        // 需要 p-1 .. p+2
        val maxFrames = ((midLen - 3 - midPos) / ratio).toInt() + 2
        if (maxFrames > 0) ensureOut(maxFrames)
        var n = 0
        while (midPos + 2 < midLen) {
            val i = midPos.toInt()
            val t = (midPos - i).toFloat()
            val o = n * channels
            for (c in 0 until channels) {
                val p0 = mid[(i - 1) * channels + c]
                val p1 = mid[i * channels + c]
                val p2 = mid[(i + 1) * channels + c]
                val p3 = mid[(i + 2) * channels + c]
                out[o + c] = p1 + 0.5f * t * (p2 - p0 + t * (2f * p0 - 5f * p1 + 4f * p2 - p3 + t * (3f * (p1 - p2) + p3 - p0)))
            }
            n++
            midPos += ratio
        }
        outLen = n
        // 留下 p-1 起的部分
        val keep = (midPos.toInt() - 1).coerceAtLeast(0)
        if (keep > 0) {
            System.arraycopy(mid, keep * channels, mid, 0, (midLen - keep) * channels)
            midLen -= keep
            midPos -= keep
        }
    }

    private fun ensureOut(frames: Int) {
        if (out.size < frames * channels) out = FloatArray(frames * channels + 1024)
    }
}
