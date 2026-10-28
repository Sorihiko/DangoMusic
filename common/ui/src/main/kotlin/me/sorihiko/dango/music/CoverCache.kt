package me.sorihiko.dango.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.gfx.Texture

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * 多张封面的缓存（封面墙、列表缩略图用）：
 * 后台下载 + 裁成正方形，渲染线程上传成纹理。
 *
 * - 纹理大约保留 [MAX] 张，超出时删最久没画过的；但最近几帧还在画的不删
 *   （排行榜 + 大歌单一屏的封面可能比 [MAX] 多，以前会一边删一边重新下载，来回折腾）。
 * - 下载好的图片另外在内存里多留一些（[MAX_SOURCES] 张）：纹理删了以后再滚回来，直接重新上传，不用再下载。
 */
object CoverCache {

    /** 纹理大约保留多少张（128×128，一张 64 KB 显存） */
    private const val MAX = 160
    /** 下载好的图片保留多少张（一张 64 KB 内存） */
    private const val MAX_SOURCES = 400
    /** 最近这么久（毫秒）里画过的封面算"正在用"，超出上限也不删 */
    private const val IN_USE_MS = 500L
    /** 每次 upload() 最多上传几张 */
    private const val UPLOADS_PER_CALL = 8
    /**
     * 下载线程：后加的任务先做（屏幕上正在看的封面优先，滚过去的旧请求排后面）
     */
    private val worker = ThreadPoolExecutor(
        3, 3, 0L, TimeUnit.MILLISECONDS,
        object : LinkedBlockingDeque<Runnable>() {
            override fun offer(e: Runnable): Boolean = offerFirst(e)
        },
    ) { r -> Thread(r, "Dango-CoverCache").apply { isDaemon = true } }

    private val requested = ConcurrentHashMap.newKeySet<String>()
    /** 下载失败的时间：过一会儿再重试（以前失败一次就再也不试了，封面会一直空着） */
    private val failedAt = ConcurrentHashMap<String, Long>()
    private const val RETRY_MS = 8000L
    private val failLogs = AtomicInteger()
    private val ready = ConcurrentLinkedQueue<Pair<String, BufferedImage>>()

    // 只在渲染线程访问
    private val textures = object : LinkedHashMap<String, Texture>(16, 0.75f, true) {}
    /** 每张纹理最后一次被画的时间（毫秒）。按时间算，不按帧：upload() 每帧可能被调好几次 */
    private val lastUsed = HashMap<String, Long>()
    private var now = 0L
    /** 已下载的正方形原图：纹理删了以后重新上传 / 做圆角版本用。按最久没用的先删 */
    private val sources = object : LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>) = size > MAX_SOURCES
    }
    private val roundQueued = HashSet<String>()

    /** 圆角半径占边长的比例（Song card / 灵动岛用） */
    private const val ROUND = 0.2f

    /** 渲染线程每帧调用：把下载好的图片上传成纹理，超出上限就删掉最久没画的 */
    fun upload() {
        now = System.currentTimeMillis()
        // 一次最多传几张，一下子滚出很多封面时不会卡一下
        for (i in 0 until UPLOADS_PER_CALL) {
            val (key, img) = ready.poll() ?: break
            val tex = DangoClient.gfx.textures.upload("dango_cover_c", img)
            textures.put(key, tex)?.let { DangoClient.gfx.textures.delete(it) }
            lastUsed[key] = now
            if (ROUND_SUFFIX !in key) sources[key] = img
        }
        // 最久没画的排在最前面；它要是最近还在画，说明全都在用，先不删（下一帧再看）
        while (textures.size > MAX) {
            val eldest = textures.entries.iterator().next()
            if (now - (lastUsed[eldest.key] ?: 0L) <= IN_USE_MS) break
            DangoClient.gfx.textures.delete(eldest.value)
            requested.remove(eldest.key)
            roundQueued.remove(eldest.key)
            lastUsed.remove(eldest.key)
            textures.remove(eldest.key)
        }
    }

    /** 取封面；还没有就去下载，先返回 null（画占位） */
    fun get(key: String, url: String, loadBytes: (() -> ByteArray?)? = null): Texture? {
        textures[key]?.let {
            lastUsed[key] = now
            return it
        }
        if (url.isEmpty() && loadBytes == null) return null
        val failed = failedAt[key]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null
        if (!requested.add(key)) return null
        // 以前下载过、只是纹理被删了：直接拿内存里的图重新上传（下一帧就有）
        sources[key]?.let {
            ready.add(key to it)
            return null
        }
        worker.execute {
            var ok = false
            var why = ""
            try {
                // NetEase's resize parameter is not part of QQ/Kugou image URLs.
                val imageHost = java.net.URI(url).host.orEmpty()
                val full = if ("?" !in url && (imageHost.endsWith(".126.net") || imageHost.endsWith(".163.com"))) "$url?param=200y200" else url
                val bytes = if (loadBytes != null) loadBytes() else NeteaseApi.request(full, "image/jpeg,image/png,image/*;q=0.8")
                if (bytes == null) {
                    why = "download failed"
                } else {
                    val img = CoverImage.decode(bytes)
                    if (img == null) {
                        why = "unreadable image (${bytes.size} bytes)"
                    } else {
                        ready.add(key to square(img, 128))
                        failedAt.remove(key)
                        ok = true
                    }
                }
            } catch (e: Throwable) {
                why = e.toString()
            } finally {
                if (!ok) {
                    requested.remove(key)
                    failedAt[key] = System.currentTimeMillis()
                    if (failLogs.incrementAndGet() <= 20) DangoLogger.warn("Cover {} failed: {} ({})", key, why, url)
                }
            }
        }
        return null
    }

    fun track(t: NeteaseTrack) = get("t:${t.key}", t.coverUrl)

    private const val ROUND_SUFFIX = "#r"

    /** 圆角版封面（渲染线程调用）：原图下好后再生成一张四角透明的纹理。ratio = 圆角半径 / 边长 */
    fun rounded(key: String, url: String, ratio: Float = ROUND): Texture? {
        val rk = key + ROUND_SUFFIX + (ratio * 100).toInt()
        textures[rk]?.let {
            lastUsed[rk] = now
            return it
        }
        get(key, url)                                  // 确保原图在下载 / 已缓存
        val src = sources[key] ?: return null
        if (roundQueued.add(rk)) {
            worker.execute {
                try {
                    ready.add(rk to roundCorners(src, ratio))
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    fun trackRounded(t: NeteaseTrack, ratio: Float = ROUND) = rounded("t:${t.key}", t.coverUrl, ratio)

    /** 四个角挖成圆角（抗锯齿：边缘按覆盖率给透明度） */
    private fun roundCorners(src: BufferedImage, ratio: Float): BufferedImage {
        val w = src.width
        val h = src.height
        val r = min(w, h) * ratio
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = x + 0.5f
                val py = y + 0.5f
                // 到最近的圆角圆心的距离（不在角落区域时为 0）
                val dx = kotlin.math.max(kotlin.math.max(r - px, px - (w - r)), 0f)
                val dy = kotlin.math.max(kotlin.math.max(r - py, py - (h - r)), 0f)
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                val cover = (r - d + 0.5f).coerceIn(0f, 1f)
                val argb = src.getRGB(x, y)
                val a = (((argb ushr 24) and 0xFF) * cover).toInt()
                out.setRGB(x, y, (a shl 24) or (argb and 0xFFFFFF))
            }
        }
        return out
    }
    fun playlist(p: NeteasePlaylist) = get("p:${p.id}", p.coverUrl)

    private fun square(src: BufferedImage, size: Int): BufferedImage {
        val side = min(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(src, 0, 0, size, size, x, y, x + side, y + side, null)
        g.dispose()
        return out
    }
}
