package me.sorihiko.dango.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.gfx.Texture

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * 多张封面的缓存（封面墙、列表缩略图用）：
 * 后台下载 + 裁成正方形，渲染线程上传成纹理。
 *
 * - 纹理大约保留 [MAX] 张，超出时删最久没画过的；但最近几帧还在画的不删
 *   （排行榜 + 大歌单一屏的封面可能比 [MAX] 多，以前会一边删一边重新下载，来回折腾）。
 * - 下载好的图片另外在内存里多留一些（[MAX_SOURCES] 张）：纹理删了以后再滚回来，直接重新上传，不用再下载。
 * - 大尺寸显示的地方（封面墙中间几张、歌单头图、封面网格）另有一份 [LARGE]×[LARGE] 的高清圆角版，
 *   单独限量（[MAX_LARGE] 张纹理、[MAX_LARGE_SOURCES] 张原图），没下好之前先用 128 的小图。
 *   封面只在内存和显存里，不写硬盘。
 */
object CoverCache {

    /** 纹理大约保留多少张（128×128，一张 64 KB 显存） */
    private const val MAX = 160
    /** 下载好的图片保留多少张（一张 64 KB 内存） */
    private const val MAX_SOURCES = 400
    /** 高清封面的边长（像素） */
    private const val LARGE = 512
    /** 高清纹理大约保留多少张（512×512，一张 1 MB 显存）；最近还在画的不删 */
    private const val MAX_LARGE = 24
    /** 高清原图保留多少张（一张 1 MB 内存） */
    private const val MAX_LARGE_SOURCES = 32
    private const val LARGE_PREFIX = "L|"
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
    /** 下载好的高清原图（只用来做圆角版，不直接上传）；删掉时允许以后重新下载 */
    private val readyLarge = ConcurrentLinkedQueue<Pair<String, BufferedImage>>()
    private val largeSources = object : LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>): Boolean {
            if (size <= MAX_LARGE_SOURCES) return false
            requested.remove(eldest.key)
            return true
        }
    }

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
        while (true) {
            val (key, img) = readyLarge.poll() ?: break
            largeSources[key] = img
        }
        // 最久没画的排在最前面；它要是最近还在画，说明全都在用，先不删（下一帧再看）
        while (textures.size > MAX) {
            val eldest = textures.entries.iterator().next()
            if (now - (lastUsed[eldest.key] ?: 0L) <= IN_USE_MS) break
            evict(eldest.key, eldest.value)
        }
        // 高清纹理一张顶 16 张小的，单独限量：同样先删最久没画的，最近还在画的不删
        var large = textures.keys.count { it.startsWith(LARGE_PREFIX) }
        if (large > MAX_LARGE) {
            val iterator = textures.entries.iterator()
            while (large > MAX_LARGE && iterator.hasNext()) {
                val (key, tex) = iterator.next()
                if (!key.startsWith(LARGE_PREFIX)) continue
                if (now - (lastUsed[key] ?: 0L) <= IN_USE_MS) break
                iterator.remove()
                evict(key, tex, removeFromMap = false)
                large--
            }
        }
    }

    private fun evict(key: String, tex: Texture, removeFromMap: Boolean = true) {
        DangoClient.gfx.textures.delete(tex)
        requested.remove(key)
        roundQueued.remove(key)
        lastUsed.remove(key)
        if (removeFromMap) textures.remove(key)
    }

    /** 取封面；还没有就去下载，先返回 null（画占位） */
    fun get(key: String, url: String): Texture? {
        textures[key]?.let {
            lastUsed[key] = now
            return it
        }
        if (url.isEmpty()) return null
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
                val bytes = NeteaseApi.request(full, "image/jpeg,image/png,image/*;q=0.8")
                if (bytes == null) {
                    why = "download failed"
                } else {
                    val img = ImageIO.read(ByteArrayInputStream(bytes))
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

    /**
     * 高清圆角版（渲染线程调用）：给大尺寸显示用。高清原图只在后台下载、裁成 [LARGE] 的正方形，
     * 不单独上传无圆角的纹理。还没好时返回 null，调用方先画小图（见 [trackRoundedLarge]）
     */
    fun roundedLarge(key: String, url: String, ratio: Float): Texture? {
        val lk = LARGE_PREFIX + key
        val rk = lk + ROUND_SUFFIX + (ratio * 100).toInt()
        textures[rk]?.let {
            lastUsed[rk] = now
            return it
        }
        val src = largeSource(lk, url) ?: return null
        if (roundQueued.add(rk)) {
            worker.execute {
                try {
                    ready.add(rk to roundCorners(src, ratio))
                } catch (_: Exception) {
                    // 做不出来就继续用小图
                }
            }
        }
        return null
    }

    /** 大尺寸显示的歌曲封面：高清版好了用高清版，否则先用小图 */
    fun trackRoundedLarge(t: NeteaseTrack, ratio: Float) =
        roundedLarge("t:${t.key}", t.coverUrl, ratio) ?: trackRounded(t, ratio)

    /** 大尺寸显示的歌单封面：高清版好了用高清版，否则先用小图 */
    fun playlistRoundedLarge(p: NeteasePlaylist, ratio: Float) =
        roundedLarge("p:${p.id}", p.coverUrl, ratio) ?: playlistRounded(p, ratio)

    private fun largeSource(key: String, url: String): BufferedImage? {
        largeSources[key]?.let { return it }
        if (url.isEmpty()) return null
        val failed = failedAt[key]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null
        if (!requested.add(key)) return null
        worker.execute {
            var ok = false
            var why = ""
            val full = sizedUrl(url, LARGE)
            try {
                fun fetch(u: String) = NeteaseApi.request(u, "image/jpeg,image/png,image/*;q=0.8")
                // 改过尺寸的地址取不到（比如 QQ 的个别专辑没有 500 的图）就退回原地址
                var bytes = fetch(full)
                var img = bytes?.let { ImageIO.read(ByteArrayInputStream(it)) }
                if (img == null && full != url) {
                    bytes = fetch(url)
                    img = bytes?.let { ImageIO.read(ByteArrayInputStream(it)) }
                }
                if (img == null) {
                    why = if (bytes == null) "download failed" else "unreadable image (${bytes.size} bytes)"
                } else {
                    // 原图比 LARGE 还小就按原图大小，不硬放大
                    readyLarge.add(key to square(img, min(LARGE, min(img.width, img.height)).coerceAtLeast(1)))
                    failedAt.remove(key)
                    ok = true
                }
            } catch (e: Throwable) {
                why = e.toString()
            } finally {
                if (!ok) {
                    requested.remove(key)
                    failedAt[key] = System.currentTimeMillis()
                    if (failLogs.incrementAndGet() <= 20) DangoLogger.warn("Large cover {} failed: {} ({})", key, why, full)
                }
            }
        }
        return null
    }

    private val qqSize = Regex("R\\d{2,4}x\\d{2,4}M")
    private val neteaseParam = Regex("param=\\d{2,4}y\\d{2,4}")

    /** 按平台的规则要 [px] 像素的图：网易云加 param，QQ 换 R300x300；认不出的原样用 */
    internal fun sizedUrl(url: String, px: Int): String {
        val host = try { java.net.URI(url).host.orEmpty() } catch (_: Exception) { return url }
        return when {
            host.endsWith(".126.net") || host.endsWith(".163.com") -> when {
                "?" !in url -> "$url?param=${px}y$px"
                neteaseParam.containsMatchIn(url) -> url.replace(neteaseParam, "param=${px}y$px")
                // 带了别的参数但没有尺寸：补上尺寸，免得下载几千像素的原图
                else -> "$url&param=${px}y$px"
            }
            host.endsWith("gtimg.cn") -> url.replace(qqSize, if (px > 300) "R500x500M" else "R300x300M")
            else -> url
        }
    }

    /** 已经下载好的原图（取颜色用，渲染线程调用）；还没下好是 null */
    fun image(key: String): BufferedImage? = sources[key]

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
    fun playlistRounded(p: NeteasePlaylist, ratio: Float = ROUND) = rounded("p:${p.id}", p.coverUrl, ratio)

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
