package me.sorihiko.dango.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.gfx.Texture

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * 当前歌曲的封面（左上角 Now playing 用）：后台下载并裁成正方形，渲染线程上传成纹理；
 * 顺便算出封面主色给跳动的小柱子用。
 */
class CoverArt {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-Cover").apply { isDaemon = true } }

    private data class Pending(val key: String, val sequence: Long, val image: BufferedImage, val colors: List<Int>, val accent: Int)
    @Volatile private var pending: Pending? = null
    @Volatile private var requestSequence = 0L
    private var requestedKey: String? = null

    var location: Texture? = null
        private set
    var shownKey: String? = null
        private set
    /** 封面换上的时间，用于淡入 */
    var shownAtNanos = 0L
        private set
    /** 从封面取出的主色（ARGB，已调亮），左上角跳动小柱子用 */
    var accent: Int = DEFAULT_ACCENT
        private set

    /** 后台提取的代表色，主色在前；无有效像素或未加载时为空，由调用方使用配置颜色。 */
    var palette: List<Int> = emptyList()
        private set

    companion object {
        const val DEFAULT_ACCENT = 0xFFF2A7C3.toInt()
    }

    /** 按封面地址加载（同一首歌只加载一次） */
    fun requestUrl(key: String, url: String) {
        if (key == requestedKey) return
        clear()
        requestedKey = key
        val sequence = requestSequence
        worker.execute {
            val img = try {
                download(if ("?" in url) url else "$url?param=300y300")?.let { squareResize(it) }
            } catch (_: Exception) {
                null
            }
            if (img != null) {
                val colors = colorsOf(img)
                if (requestSequence == sequence) pending = Pending(key, sequence, img, colors.first, colors.second)
            }
        }
    }

    /** 渲染线程：把后台准备好的图片上传成纹理 */
    fun upload() {
        val result = pending ?: return
        pending = null
        if (result.sequence != requestSequence || result.key != requestedKey) return
        val img = result.image
        val key = result.key
        val textures = DangoClient.gfx.textures
        location?.let { textures.delete(it) }
        location = textures.upload("dango_cover", img)
        shownKey = key
        shownAtNanos = System.nanoTime()
        palette = result.colors
        accent = result.accent
    }

    /**
     * 先选真实彩色；不足三种时，仅用明显的浅中性色补充。
     */
    private fun colorsOf(img: BufferedImage): Pair<List<Int>, Int> {
        val bins = 24
        val accentWt = DoubleArray(bins)
        val accentRs = DoubleArray(bins)
        val accentGs = DoubleArray(bins)
        val accentBs = DoubleArray(bins)
        var accentTotal = 0.0
        var accentCount = 0
        val wt = DoubleArray(bins + 2)
        val rs = DoubleArray(bins + 2)
        val gs = DoubleArray(bins + 2)
        val bs = DoubleArray(bins + 2)
        val hsb = FloatArray(3)
        val area = DoubleArray(bins + 2)
        var visible = 0.0
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val argb = img.getRGB(x, y)
                val alpha = (argb ushr 24) / 255.0
                if (alpha >= 0.1) {
                    visible += alpha
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    val b = argb and 0xFF
                    java.awt.Color.RGBtoHSB(r, g, b, hsb)
                    // 播放信息主色沿用原有权重和透明度阈值。
                    if ((argb ushr 24) >= 128) {
                        val w = (hsb[1] * hsb[1] * hsb[2]).toDouble()
                        val k = (hsb[0] * bins).toInt().coerceIn(0, bins - 1)
                        accentWt[k] += w
                        accentRs[k] += r * w
                        accentGs[k] += g * w
                        accentBs[k] += b * w
                        accentTotal += w
                        accentCount++
                    }
                    val chroma = maxOf(r, g, b) - minOf(r, g, b)
                    // 接近黑色时相对饱和度不可靠，同时保留真正高饱和的深色。
                    val neutral = hsb[1] < 0.12f || (hsb[1] < 0.65f && (chroma < 24 || (hsb[2] < 0.65f && chroma < 48)))
                    val w = alpha * (if (neutral) 1.0 else 1.0 + 0.5 * hsb[1])
                    // 浅中性独立统计，避免阴影拉低平均值后又被强行抬白。
                    val lightNeutral = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 >= 0.60
                    val k = if (neutral) bins + (if (lightNeutral) 1 else 0) else (hsb[0] * bins).toInt().coerceIn(0, bins - 1)
                    area[k] += alpha
                    wt[k] += w
                    rs[k] += r * w
                    gs[k] += g * w
                    bs[k] += b * w
                }
                x += 2
            }
            y += 2
        }
        val accent = if (accentCount == 0 || accentTotal / accentCount < 0.04) {
            0xFFE8E8F0.toInt()
        } else {
            var best = 0
            var bestW = -1.0
            for (k in 0 until bins) {
                val w = accentWt[k] + 0.5 * (accentWt[(k + 1) % bins] + accentWt[(k + bins - 1) % bins])
                if (w > bestW) {
                    bestW = w
                    best = k
                }
            }
            val w = accentWt[best].coerceAtLeast(1e-6)
            val r = (accentRs[best] / w).toInt().coerceIn(0, 255)
            val g = (accentGs[best] / w).toInt().coerceIn(0, 255)
            val b = (accentBs[best] / w).toInt().coerceIn(0, 255)
            java.awt.Color.RGBtoHSB(r, g, b, hsb)
            java.awt.Color.HSBtoRGB(hsb[0], hsb[1].coerceIn(0.35f, 0.75f), hsb[2].coerceAtLeast(0.92f)) or (0xFF shl 24)
        }
        if (visible == 0.0) return emptyList<Int>() to accent
        val ranked = (0 until bins).sortedByDescending { k ->
            wt[k]
        }
        val colors = mutableListOf<Int>()
        val hues = mutableListOf<Float>()
        for (k in ranked) {
            // 保留小面积标题颜色，过滤占比低于 0.5% 的零散杂色。
            if (area[k] < visible * 0.005) continue
            java.awt.Color.RGBtoHSB((rs[k] / wt[k]).toInt(), (gs[k] / wt[k]).toInt(), (bs[k] / wt[k]).toInt(), hsb)
            // 混合像素的平均色仍可能是暗部弱偏色，提亮前将其排除。
            if (hsb[1] < 0.65f && hsb[2] < 0.65f && hsb[1] * hsb[2] * 255 < 48) continue
            if (hues.any { hue ->
                val distance = kotlin.math.abs(hue - hsb[0])
                min(distance, 1f - distance) < 1f / 8f
            }) continue
            colors += java.awt.Color.HSBtoRGB(hsb[0], hsb[1].coerceAtMost(0.75f), hsb[2].coerceAtLeast(0.92f))
            hues += hsb[0]
            if (colors.size == 4) break
        }
        val light = bins + 1
        if (colors.size in 1..2 && area[light] >= visible * 0.05) {
            val r = (rs[light] / wt[light]).toInt()
            val g = (gs[light] / wt[light]).toInt()
            val b = (bs[light] / wt[light]).toInt()
            java.awt.Color.RGBtoHSB(r, g, b, hsb)
            // 仅真正近灰才去色，保留米白、浅灰绿。
            val saturation = if (maxOf(r, g, b) - minOf(r, g, b) <= 12) 0f else hsb[1]
            colors += java.awt.Color.HSBtoRGB(hsb[0], saturation, maxOf(hsb[2], minOf(0.92f, hsb[2] + 0.08f)))
        }
        if (colors.isEmpty()) {
            // 无彩色时取封面自身灰阶；纯暗图略微提亮。
            val weight = wt[bins] + wt[light]
            val value = if (weight > 0) ((0.2126 * (rs[bins] + rs[light]) + 0.7152 * (gs[bins] + gs[light]) + 0.0722 * (bs[bins] + bs[light])) / weight / 255).toFloat() else 0f
            colors += java.awt.Color.HSBtoRGB(0f, 0f, minOf(1f, value + 0.08f))
        }
        return colors.toList() to accent
    }

    fun clear() {
        requestSequence++
        location?.let { DangoClient.gfx.textures.delete(it) }
        location = null
        shownKey = null
        requestedKey = null
        pending = null
        shownAtNanos = 0L
        accent = DEFAULT_ACCENT
        palette = emptyList()
    }

    // ---------------------------------------------------------------- 工具

    /** 居中裁成正方形（YouTube 缩略图是 16:9 的），再平滑缩到 128×128 */
    private fun squareResize(src: BufferedImage): BufferedImage {
        val side = min(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val size = 128
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, size, size, x, y, x + side, y + side, null)
        g.dispose()
        return out
    }

    private fun download(url: String): BufferedImage? {
        val bytes = httpGet(url) ?: return null
        return ImageIO.read(ByteArrayInputStream(bytes))
    }

    private fun httpGet(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 DangoMusic")
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
