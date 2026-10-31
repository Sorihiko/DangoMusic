package me.sorihiko.dango.font

import me.sorihiko.dango.DangoLogger

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.EXTFramebufferObject
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GLContext

import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 中日韩文字 / 符号字体：用系统自带字体（微软雅黑 → 日文 / 韩文字体 → Segoe UI Symbol），
 * 遇到没见过的字才现场画出来，塞进 1024×1024 的贴图页里（满了开新页），之后直接复用。
 * mod 不需要打包任何字体文件。
 * 安卓启动器（ZL2、Pojav 等）里的 Java 找不到手机的系统字体，这时直接去系统字体目录里加载中文字体文件。
 *
 * 只能在渲染线程调用。
 */
object CjkFont {

    /** 样式：普通 / 粗体 / 海报体（文闪用：英文 Impact，中文雅黑粗体，按更大的字号生成） */
    const val PLAIN = 0
    const val BOLD = 1
    const val DISPLAY = 2

    /** 字形贴图里的字号（像素），屏幕上按需缩放 */
    private fun baseOf(style: Int) = if (style == DISPLAY) 96f else 48f
    private const val PAGE = 1024
    private const val PAD = 4

    class Glyph(
        val page: Int,
        val u0: Float, val v0: Float, val u1: Float, val v1: Float,
        /** 以下单位都是 base 字号下的像素 */
        val w: Float, val h: Float,
        val left: Float, val top: Float,
        val adv: Float,
        val base: Float,
    )

    private val displayFamilies = listOf("Impact", "Anton", "Bebas Neue", "Arial Black", "Segoe UI Black")

    private val families = listOf(
        "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC",
        "Yu Gothic UI", "Meiryo UI", "Meiryo",
        "Malgun Gothic",
        "Noto Sans CJK SC", "Source Han Sans SC", "SimHei", "MS Gothic",
        "Segoe UI Symbol", "Segoe UI", "Arial Unicode MS",
    )

    private val frc = FontRenderContext(null, true, true)
    private val fontLists = arrayOfNulls<List<Font>>(3)

    private val glyphs = HashMap<Int, Glyph?>()
    private val pages = ArrayList<Int>()
    private val dirty = ArrayList<Boolean>()
    private var px = PAD
    private var py = PAD
    private var rowH = 0

    private val available: Set<String> by lazy {
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames(Locale.ENGLISH).map { it.lowercase() }.toHashSet()
        } catch (_: Exception) {
            hashSetOf<String>()
        }
    }

    private fun fonts(style: Int): List<Font> {
        fontLists[style]?.let { return it }
        val base = baseOf(style)
        val awtStyle = if (style == PLAIN) Font.PLAIN else Font.BOLD
        val list = mutableListOf<Font>()
        if (style == DISPLAY) {
            list += displayFamilies.filter { it.lowercase() in available }.map { Font(it, Font.PLAIN, 1).deriveFont(base) }
        }
        list += families.filter { it.lowercase() in available }.map { Font(it, awtStyle, 1).deriveFont(base) }
        if (list.none { it.canDisplay('中') }) list += fileFonts.map { it.deriveFont(awtStyle, base) }
        // Java 的逻辑字体在 Windows 上会映射到系统的中文字体，作为最后的兜底
        list += Font(Font.DIALOG, awtStyle, 1).deriveFont(base)
        fontLists[style] = list
        return list
    }

    /** 按名字认出来的中文字体文件，越靠前越好 */
    private val fileHints = listOf(
        "notosanscjk", "notosanssc", "notosanshans", "sourcehansans", "misans", "harmonyos_sans_sc",
        "hyqihei", "droidsansfallback", "wqy", "cjk", "fallback",
    )

    private val fontDirs = listOf(
        "/system/fonts", "/system/product/fonts", "/product/fonts", "/system_ext/fonts", "/data/fonts/files",
        "/usr/share/fonts", "/usr/local/share/fonts", System.getProperty("user.home") + "/.local/share/fonts",
        System.getProperty("user.home") + "/.fonts",
    )

    /** 找不到任何能显示中文的系统字体时，直接从字体目录加载字体文件（只找一次） */
    private val fileFonts: List<Font> by lazy {
        val files = ArrayList<File>()
        fun scan(dir: File, depth: Int) {
            val list = try { dir.listFiles() } catch (_: Exception) { null } ?: return
            for (f in list) {
                val n = f.name.lowercase()
                if (f.isDirectory) {
                    if (depth < 3) scan(f, depth + 1)
                } else if ((n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc")) && fileHints.any { it in n }) {
                    files += f
                }
            }
        }
        for (d in fontDirs) scan(File(d), 0)
        files.sortWith(compareBy<File>({ f -> fileHints.indexOfFirst { it in f.name.lowercase() } },
            { if ("regular" in it.name.lowercase()) 0 else 1 }, { it.name }))
        val out = ArrayList<Font>()
        for (f in files) {
            if (out.size >= 2) break
            val font = try { Font.createFont(Font.TRUETYPE_FONT, f) } catch (_: Throwable) { null } ?: continue
            if (font.canDisplay('中')) out += font
        }
        if (out.isEmpty()) {
            DangoLogger.warn("Dango Music: no CJK font found on this system; Chinese / Japanese / Korean text cannot be shown")
        } else {
            DangoLogger.info("Dango Music: loaded CJK font from file: {}", out.joinToString { it.fontName })
        }
        out
    }

    fun glyph(c: Char, style: Int): Glyph? {
        val key = c.code * 3 + style
        if (glyphs.containsKey(key)) return glyphs[key]
        val g = try {
            render(c, style)
        } catch (_: Exception) {
            null
        }
        glyphs[key] = g
        return g
    }

    fun width(s: String, size: Float, style: Int): Float {
        var w = 0f
        for (c in s) {
            val g = glyph(c, style) ?: continue
            w += g.adv * size / g.base
        }
        return w
    }

    private fun render(c: Char, style: Int): Glyph? {
        val base = baseOf(style)
        val font = fonts(style).firstOrNull { it.canDisplay(c) } ?: return null
        val gv = font.createGlyphVector(frc, charArrayOf(c))
        val adv = gv.getGlyphMetrics(0).advanceX
        val vb = gv.visualBounds
        if (vb.width <= 0.0 || vb.height <= 0.0) {
            return Glyph(-1, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, adv, base)   // 空格之类
        }
        val left = floor(vb.x).toInt() - 1
        val top = floor(vb.y).toInt() - 1
        val w = ceil(vb.x + vb.width).toInt() + 1 - left
        val h = ceil(vb.y + vb.height).toInt() + 1 - top
        if (w > PAGE - PAD * 2 || h > PAGE - PAD * 2) return null

        if (pages.isEmpty()) newPage()
        if (px + w + PAD > PAGE) {
            px = PAD
            py += rowH + PAD
            rowH = 0
        }
        if (py + h + PAD > PAGE) newPage()

        val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = Color.WHITE
        g.drawGlyphVector(gv, -left.toFloat(), -top.toFloat())
        g.dispose()

        val page = pages.size - 1
        upload(pages[page], px, py, w, h, (img.raster.dataBuffer as DataBufferByte).data)
        dirty[page] = true

        val glyph = Glyph(
            page,
            px.toFloat() / PAGE, py.toFloat() / PAGE,
            (px + w).toFloat() / PAGE, (py + h).toFloat() / PAGE,
            w.toFloat(), h.toFloat(),
            left.toFloat(), top.toFloat(),
            adv,
            base,
        )
        px += w + PAD
        rowH = max(rowH, h)
        return glyph
    }

    // ---------------------------------------------------------------- 贴图

    private val canMipmap: Boolean by lazy {
        val caps = GLContext.getCapabilities()
        caps.OpenGL30 || caps.GL_EXT_framebuffer_object
    }

    private fun genMipmap() {
        if (GLContext.getCapabilities().OpenGL30) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D)
        else EXTFramebufferObject.glGenerateMipmapEXT(GL11.GL_TEXTURE_2D)
    }

    private fun newPage() {
        val prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        val id = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
        // 白色 RGB + 字形透明度：不管当前的纹理环境是什么模式，颜色都由顶点颜色决定，不会变黑
        val zero = BufferUtils.createByteBuffer(PAGE * PAGE * 4)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, PAGE, PAGE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zero)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        if (canMipmap) {
            // 缩小显示时用多级纹理，字不会发虚闪烁；只到第 2 级，避免相邻字形串色
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 2)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR)
            genMipmap()
        } else {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev)
        pages += id
        dirty += false
        px = PAD
        py = PAD
        rowH = 0
    }

    private fun upload(tex: Int, x: Int, y: Int, w: Int, h: Int, data: ByteArray) {
        val buf = BufferUtils.createByteBuffer(w * h * 4)
        for (i in 0 until w * h) {
            buf.put(0xFF.toByte()).put(0xFF.toByte()).put(0xFF.toByte()).put(data[i])
        }
        buf.flip()
        val prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        val prevAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT)
        val prevRow = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign)
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRow)
    }

    private fun bindPage(i: Int) {
        // 直接用 GL 绑定：MC 的状态缓存可能和实际状态对不上
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, pages[i])
        if (dirty[i]) {
            if (canMipmap) genMipmap()
            dirty[i] = false
        }
    }

    // ---------------------------------------------------------------- 绘制（固定管线，调用方设好投影、颜色、混合）

    /** 从 x 开始、以 baseline 为基线画一段文字 */
    fun draw(s: String, x: Float, baseline: Float, size: Float, style: Int) {
        val list = s.map { glyph(it, style) }   // 先把字形都准备好（上传贴图不能放在 glBegin 里）
        var pen = x
        var bound = -1
        var begun = false
        for (g in list) {
            if (g == null) continue
            val scale = size / g.base
            if (g.page >= 0) {
                if (g.page != bound) {
                    if (begun) GL11.glEnd()
                    bindPage(g.page)
                    bound = g.page
                    GL11.glBegin(GL11.GL_QUADS)
                    begun = true
                }
                quad(g, pen + g.left * scale, baseline + g.top * scale, scale)
            }
            pen += g.adv * scale
        }
        if (begun) GL11.glEnd()
    }

    /** 图标：把字形的可见部分居中放在 (cx, cy) */
    fun drawCentered(s: String, cx: Float, cy: Float, size: Float, style: Int) {
        val list = s.mapNotNull { glyph(it, style) }
        if (list.isEmpty()) return
        val scale = size / list[0].base
        var total = 0f
        list.forEachIndexed { i, g -> total += if (i == list.size - 1) g.w else g.adv }
        var pen = cx - total * scale / 2f
        for (g in list) {
            if (g.page >= 0) {
                bindPage(g.page)
                GL11.glBegin(GL11.GL_QUADS)
                quad(g, pen, cy - g.h * scale / 2f, scale)
                GL11.glEnd()
            }
            pen += g.adv * scale
        }
    }

    private fun quad(g: Glyph, x: Float, y: Float, scale: Float) {
        val w = g.w * scale
        val h = g.h * scale
        GL11.glTexCoord2f(g.u0, g.v0); GL11.glVertex2f(x, y)
        GL11.glTexCoord2f(g.u0, g.v1); GL11.glVertex2f(x, y + h)
        GL11.glTexCoord2f(g.u1, g.v1); GL11.glVertex2f(x + w, y + h)
        GL11.glTexCoord2f(g.u1, g.v0); GL11.glVertex2f(x + w, y)
    }
}


