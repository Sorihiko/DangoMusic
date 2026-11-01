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
 * 顺便算出封面主色给跳动的小柱子用，以及几种代表色给歌词配色用。
 */
class CoverArt {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-Cover").apply { isDaemon = true } }

    @Volatile private var pendingKey: String? = null
    @Volatile private var pendingImage: BufferedImage? = null
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
    /** 封面的几种代表色（ARGB，已调亮），最显眼的在前；歌词"封面主色 / 封面渐变"用 */
    var palette: List<Int> = emptyList()
        private set

    companion object {
        const val DEFAULT_ACCENT = 0xFFF2A7C3.toInt()
        /** 歌词渐变最多用几种封面颜色 */
        private const val MAX_COLORS = 4
    }

    /** 按封面地址加载（同一首歌只加载一次） */
    fun requestUrl(key: String, url: String) {
        if (key == requestedKey) return
        requestedKey = key
        worker.execute {
            val img = try {
                download(if ("?" in url) url else "$url?param=300y300")?.let { squareResize(it) }
            } catch (_: Exception) {
                null
            }
            if (img != null && requestedKey == key) {
                pendingImage = img
                pendingKey = key
            }
        }
    }

    /** 渲染线程：把后台准备好的图片上传成纹理 */
    fun upload() {
        val img = pendingImage ?: return
        val key = pendingKey
        pendingImage = null
        val textures = DangoClient.gfx.textures
        location?.let { textures.delete(it) }
        location = textures.upload("dango_cover", img)
        shownKey = key
        shownAtNanos = System.nanoTime()
        val colors = try { paletteOf(img) } catch (_: Exception) { emptyList() }
        accent = colors.firstOrNull() ?: DEFAULT_ACCENT
        palette = colors
    }

    /**
     * 封面的代表色：按色相分 24 格统计，越鲜艳越亮的像素权重越大（相邻两格一起算，颜色落在格子边界也不会被拆开）。
     * 第一个是权重最大的那格（主色），之后按权重依次挑和已选颜色色相差得够远的格子，最多 [MAX_COLORS] 个；
     * 太弱的不要。每个颜色都调成足够亮、饱和度适中（在游戏画面上看得清）。几乎没颜色的封面只给一个浅灰白。
     */
    private fun paletteOf(img: BufferedImage): List<Int> {
        val bins = 24
        val wt = DoubleArray(bins)
        val rs = DoubleArray(bins)
        val gs = DoubleArray(bins)
        val bs = DoubleArray(bins)
        val hsb = FloatArray(3)
        var total = 0.0
        var count = 0
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val argb = img.getRGB(x, y)
                if ((argb ushr 24) >= 128) {
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    val b = argb and 0xFF
                    java.awt.Color.RGBtoHSB(r, g, b, hsb)
                    val w = (hsb[1] * hsb[1] * hsb[2]).toDouble()
                    val k = ((hsb[0] * bins).toInt()).coerceIn(0, bins - 1)
                    wt[k] += w
                    rs[k] += r * w
                    gs[k] += g * w
                    bs[k] += b * w
                    total += w
                    count++
                }
                x += 2
            }
            y += 2
        }
        if (count == 0 || total / count < 0.04) return listOf(0xFFE8E8F0.toInt())
        val score = DoubleArray(bins) { k -> wt[k] + 0.5 * (wt[(k + 1) % bins] + wt[(k + bins - 1) % bins]) }
        val picked = ArrayList<Int>()
        while (picked.size < MAX_COLORS) {
            var best = -1
            for (k in 0 until bins) {
                // 和已经挑中的颜色至少差 3 格（45°），不然两种颜色看起来差不多
                if (picked.any { p -> min((k - p + bins) % bins, (p - k + bins) % bins) < 3 }) continue
                if (best < 0 || score[k] > score[best]) best = k
            }
            if (best < 0 || wt[best] <= 0.0) break
            if (picked.isNotEmpty() && score[best] < score[picked[0]] * 0.12) break
            picked += best
        }
        val colors = picked.map { k ->
            val w = wt[k].coerceAtLeast(1e-6)
            val r = (rs[k] / w).toInt().coerceIn(0, 255)
            val g = (gs[k] / w).toInt().coerceIn(0, 255)
            val b = (bs[k] / w).toInt().coerceIn(0, 255)
            java.awt.Color.RGBtoHSB(r, g, b, hsb)
            java.awt.Color.HSBtoRGB(hsb[0], hsb[1].coerceIn(0.35f, 0.75f), hsb[2].coerceAtLeast(0.92f)) or (0xFF shl 24)
        }
        // 只有一种颜色时补一个同色相的浅色，渐变才看得出来
        if (colors.size == 1) {
            java.awt.Color.RGBtoHSB((colors[0] shr 16) and 0xFF, (colors[0] shr 8) and 0xFF, colors[0] and 0xFF, hsb)
            return colors + (java.awt.Color.HSBtoRGB(hsb[0], hsb[1] * 0.35f, 1f) or (0xFF shl 24))
        }
        return colors
    }

    fun clear() {
        location?.let { DangoClient.gfx.textures.delete(it) }
        location = null
        shownKey = null
        requestedKey = null
        pendingImage = null
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
