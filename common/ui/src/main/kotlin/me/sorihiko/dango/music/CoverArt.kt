package me.sorihiko.dango.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.playback.SessionTrack

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

    @Volatile private var pending: Pair<String, BufferedImage>? = null
    @Volatile private var requestedKey: String? = null
    private var retryAtNanos = 0L

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

    companion object {
        const val DEFAULT_ACCENT = 0xFFF2A7C3.toInt()
    }

    /** 按封面地址加载（同一首歌只加载一次） */
    fun requestUrl(key: String, url: String) {
        request(key) { download(if ("?" in url) url else "$url?param=300y300") }
    }

    fun requestTrack(track: SessionTrack) {
        val loader = track.coverBytes
        if (loader != null) request(track.key) { loader()?.let { CoverImage.decode(it) } }
        else if (track.coverUrl.isNotEmpty()) requestUrl(track.key, track.coverUrl)
    }

    private fun request(key: String, load: () -> BufferedImage?) {
        if (key == requestedKey && (shownKey == key || System.nanoTime() < retryAtNanos)) return
        requestedKey = key
        retryAtNanos = System.nanoTime() + 8_000_000_000L
        worker.execute {
            val img = try {
                load()?.let { squareResize(it) }
            } catch (_: Exception) {
                null
            }
            if (img != null && requestedKey == key) {
                pending = key to img
            }
        }
    }

    /** 渲染线程：把后台准备好的图片上传成纹理 */
    fun upload() {
        val (key, img) = pending ?: return
        pending = null
        if (key != requestedKey) return
        val textures = DangoClient.gfx.textures
        location?.let { textures.delete(it) }
        location = textures.upload("dango_cover", img)
        shownKey = key
        shownAtNanos = System.nanoTime()
        accent = try { accentOf(img) } catch (_: Exception) { DEFAULT_ACCENT }
    }

    /**
     * 封面主色：按色相分 24 格统计，越鲜艳越亮的像素权重越大，取权重最大的那格的平均色，
     * 再调成足够亮、饱和度适中的颜色（在游戏画面上看得清）。几乎没颜色的封面用浅灰白。
     */
    private fun accentOf(img: BufferedImage): Int {
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
        if (count == 0 || total / count < 0.04) return 0xFFE8E8F0.toInt()
        // 相邻两格一起算，避免颜色刚好落在格子边界被拆开
        var best = 0
        var bestW = -1.0
        for (k in 0 until bins) {
            val w = wt[k] + 0.5 * (wt[(k + 1) % bins] + wt[(k + bins - 1) % bins])
            if (w > bestW) {
                bestW = w
                best = k
            }
        }
        val w = wt[best].coerceAtLeast(1e-6)
        val r = (rs[best] / w).toInt().coerceIn(0, 255)
        val g = (gs[best] / w).toInt().coerceIn(0, 255)
        val b = (bs[best] / w).toInt().coerceIn(0, 255)
        java.awt.Color.RGBtoHSB(r, g, b, hsb)
        val rgb = java.awt.Color.HSBtoRGB(hsb[0], hsb[1].coerceIn(0.35f, 0.75f), hsb[2].coerceAtLeast(0.92f))
        return rgb or (0xFF shl 24)
    }

    fun clear() {
        location?.let { DangoClient.gfx.textures.delete(it) }
        location = null
        shownKey = null
        requestedKey = null
        pending = null
        retryAtNanos = 0L
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
