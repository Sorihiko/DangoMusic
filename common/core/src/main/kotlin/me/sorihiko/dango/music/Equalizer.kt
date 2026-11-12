package me.sorihiko.dango.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 10 段均衡器：每段一个峰值滤波器（RBJ Audio EQ Cookbook 的公式），串起来。
 * 前级增益自动算：整条频响最高抬了多少 dB 就整体压多少，最后再过一道软限幅，开大了也不爆音。
 *
 * 一次解码一个实例（滤波器有状态），输入输出都是交错的 16 位 PCM，原地改。
 */
class Equalizer(private val channels: Int, private val rate: Int) {

    companion object {
        val FREQUENCIES = floatArrayOf(32f, 64f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val LABELS = arrayOf("32", "64", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")
        const val BANDS = 10
        const val MAX_GAIN = 12f
        /** 每段大约一个八度宽 */
        private const val Q = 1.41

        /** 这组增益下整条频响最高处（dB，按 48kHz 算）；自动前级增益 = 负的这个数（最多压到 0） */
        fun peakDb(gains: FloatArray): Float {
            val rate = 48000
            val coefs = Array(BANDS) { design(FREQUENCIES[it], gains.getOrElse(it) { 0f }, rate) }
            var peak = 0.0
            // 20Hz～20kHz 取 96 个点（对数均匀）
            for (i in 0 until 96) {
                val f = 20.0 * 1000.0.pow(i / 95.0)
                var db = 0.0
                for (c in coefs) if (c != null) db += c.magnitudeDb(f, rate)
                peak = max(peak, db)
            }
            return peak.toFloat()
        }

        fun autoPreampDb(gains: FloatArray): Float = -peakDb(gains)

        private fun design(f0: Float, gainDb: Float, rate: Int): Coef? {
            if (abs(gainDb) < 0.01f || f0 >= rate * 0.45f) return null
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * f0 / rate
            val alpha = sin(w0) / (2.0 * Q)
            val cw = cos(w0)
            val a0 = 1.0 + alpha / a
            return Coef(
                ((1.0 + alpha * a) / a0).toFloat(), (-2.0 * cw / a0).toFloat(), ((1.0 - alpha * a) / a0).toFloat(),
                (-2.0 * cw / a0).toFloat(), ((1.0 - alpha / a) / a0).toFloat(),
            )
        }
    }

    /** 归一化后的系数（a0 = 1） */
    private class Coef(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float) {
        fun magnitudeDb(f: Double, rate: Int): Double {
            val w = 2.0 * PI * f / rate
            val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
            val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
            val dr = 1.0 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
            return 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
        }
    }

    private val applied = FloatArray(BANDS) { Float.NaN }
    private var coefs = arrayOfNulls<Coef>(BANDS)
    private var preamp = 1f
    /** 每段每声道的状态：x1 x2 y1 y2 */
    private val state = Array(BANDS) { FloatArray(channels * 4) }

    /** 增益变了才重新算系数；之前没开着的段从静止状态开始 */
    private fun update(gains: FloatArray) {
        var changed = false
        for (i in 0 until BANDS) if (applied[i] != gains[i]) { changed = true; break }
        if (!changed) return
        for (i in 0 until BANDS) {
            val c = design(FREQUENCIES[i], gains[i], rate)
            if (c == null) java.util.Arrays.fill(state[i], 0f)
            coefs[i] = c
            applied[i] = gains[i]
        }
        preamp = 10f.pow(autoPreampDb(gains) / 20f)
    }

    fun process(pcm: ShortArray, len: Int, gains: FloatArray) {
        update(gains)
        val frames = len / channels
        val g = preamp
        for (f in 0 until frames) {
            for (ch in 0 until channels) {
                val idx = f * channels + ch
                var x = pcm[idx] * g
                for (b in 0 until BANDS) {
                    val c = coefs[b] ?: continue
                    val s = state[b]
                    val o = ch * 4
                    val y = c.b0 * x + c.b1 * s[o] + c.b2 * s[o + 1] - c.a1 * s[o + 2] - c.a2 * s[o + 3]
                    s[o + 1] = s[o]; s[o] = x
                    s[o + 3] = s[o + 2]; s[o + 2] = y
                    x = y
                }
                pcm[idx] = limit(x)
            }
        }
        // 算出了 NaN / 无穷：滤波器状态清零，不然会一直没声
        if (broken) {
            broken = false
            for (st in state) java.util.Arrays.fill(st, 0f)
        }
    }

    private var broken = false

    /** 软限幅：26000 以下原样，以上用 tanh 压进剩下的余量里 */
    private fun limit(x: Float): Short {
        if (!x.isFinite()) {
            broken = true
            return 0
        }
        val knee = 26000f
        val ax = abs(x)
        if (ax <= knee) return x.toInt().toShort()
        val room = 32767f - knee
        val y = knee + room * tanh((ax - knee) / room)
        return (if (x < 0) -y else y).toInt().toShort()
    }
}

/** 均衡器预设。名字是界面上显示的英文原文（翻译在 zh_cn.tsv） */
object EqPresets {
    class Preset(val name: String, val gains: FloatArray)

    val ALL = listOf(
        Preset("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("Bass Boost", floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("Bass Destroyer", floatArrayOf(12f, 12f, 10f, 7f, 3f, 0f, -2f, -4f, -4f, -5f)),
        Preset("Treble Boost", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 5f, 6f)),
        Preset("Vocal", floatArrayOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f)),
        Preset("Rock", floatArrayOf(4f, 3f, 1f, 0f, -1f, 0f, 2f, 3f, 4f, 4f)),
        Preset("Electronic", floatArrayOf(5f, 4f, 2f, 0f, -1f, 0f, 1f, 3f, 4f, 5f)),
        Preset("Classical", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, -2f, -3f, -3f, -4f)),
        Preset("Loudness", floatArrayOf(5f, 4f, 1f, 0f, -1f, 0f, -1f, 0f, 3f, 4f)),
        Preset("V-Shape", floatArrayOf(5f, 3f, 1f, -1f, -3f, -3f, -1f, 1f, 3f, 5f)),
        Preset("Night", floatArrayOf(-3f, -2f, 0f, 2f, 3f, 3f, 2f, 0f, -2f, -4f)),
    )

    /** 和哪个预设一模一样（拖过滑条就谁都不是） */
    fun matching(gains: FloatArray): Preset? = ALL.firstOrNull { p -> p.gains.indices.all { abs(p.gains[it] - gains[it]) < 0.01f } }
}
