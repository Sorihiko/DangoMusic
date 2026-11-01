package me.sorihiko.dango.modern

import me.sorihiko.dango.font.CjkFont
import me.sorihiko.dango.gfx.CjkRun
import me.sorihiko.dango.gfx.Gfx
import me.sorihiko.dango.gfx.GlowPass
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.gfx.HudCanvas
import me.sorihiko.dango.gfx.TextCanvas
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.gfx.Textures
import me.sorihiko.dango.gfx.WorldView
import me.sorihiko.dango.lyrics.DeepGlow
import me.sorihiko.dango.ui.GlassRenderContext

import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13

import java.awt.image.BufferedImage
import kotlin.math.max

/** 高版本的绘图实现：OpenGL 3.2 core（自己的着色器 + [Imm]）+ 原版的 Font / TextureManager */
object ModernGfx : Gfx {

    private val mc: Minecraft get() = Minecraft.getInstance()

    override val textures: Textures = ModernTextures

    override fun newCanvas(): GuiCanvas = GlassRenderContext()

    override fun uiTextWidth(text: String, size: Float, font: String): Float =
        ModernGl.font(font).getTextWidth(text, size)

    override fun cjkWidth(text: String, size: Float, style: Int): Float = CjkFont.width(text, size, style)

    override fun cjkPreload(c: Char, style: Int) {
        CjkFont.glyph(c, style)
    }

    override fun drawCjkRuns(runs: List<CjkRun>, scale: Float) {
        if (runs.isEmpty()) return
        val k = scale
        val window = mc.window
        val state = GlState()
        val projection = Projection.screen(window.width.toFloat(), window.height.toFloat())
        try {
            Fixed.reset(Matrix4f().translation(0f, 0f, -2000f).scale(k, k, 1f))
            textState()
            for (r in runs) {
                val c = r.clip
                if (c != null) {
                    Gl.scissor(true)
                    Gl.scissorBox(
                        (c.x * k).toInt(),
                        (window.height - c.bottom * k).toInt(),
                        max(0, (c.width * k).toInt()),
                        max(0, (c.height * k).toInt()),
                    )
                } else {
                    Gl.scissor(false)
                }
                Fixed.color(r.r, r.g, r.b, r.a.coerceIn(0f, 1f))
                if (r.centered) CjkFont.drawCentered(r.s, r.x, r.y, r.size, r.style)
                else CjkFont.draw(r.s, r.x, r.y, r.size, r.style)
            }
        } finally {
            Fixed.color(1f, 1f, 1f, 1f)
            projection.restore()
            state.restore()
        }
    }

    override fun guarded(block: () -> Unit) {
        val state = GlState()
        Fixed.reset()
        Fixed.color(1f, 1f, 1f, 1f)
        Gl.activeTexture(GL13.GL_TEXTURE0)
        try {
            block()
        } finally {
            Fixed.reset()
            state.restore()
        }
    }

    override val text: TextCanvas = ModernText
    override val world: WorldView = ModernWorld
    override val glow: GlowPass = DeepGlow()
    override val hud: HudCanvas = ModernHud

    /** 画文字用的状态：透明混合、不测深度、不剔除背面（和 1.8.9 一样） */
    internal fun textState() {
        Gl.blend(true)
        Gl.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
        Gl.depthTest(false)
        Gl.cull(false)
        Gl.activeTexture(GL13.GL_TEXTURE0)
    }

    /** 用原版字体画一段像素字（[pose] 是模型矩阵），画完重新设好我们自己的状态 */
    internal fun vanillaText(text: String, x: Float, y: Float, argb: Int, shadow: Boolean, pose: Matrix4f) {
        // 阴影自己分开画：原版是把阴影和字放进同一批、靠前后距离排序决定谁在上面，
        // 歌词的坐标是翻转过的（和 1.8.9 一样 scale(-s, -s, s)），排序后阴影会跑到字上面
        if (shadow) {
            val shadowColor = ((argb and 0xFCFCFC) shr 2) or (argb and 0xFF000000.toInt())
            drawVanilla(text, x + 1f, y + 1f, shadowColor, pose)
        }
        drawVanilla(text, x, y, argb, pose)
    }

    private fun drawVanilla(text: String, x: Float, y: Float, argb: Int, pose: Matrix4f) {
        McCompat.drawText(text, x, y, argb, pose)
    }
}

/** 临时换成自己的投影矩阵（画屏幕上的东西用），[restore] 换回原来的 */
internal class Projection private constructor() {
    fun restore() {
        McCompat.popModelView()
        McCompat.popScreenProjection()
    }

    companion object {
        /** 原点在左上角、单位是 [w]×[h] 的正交投影（近 1000、远 3000，和 1.8.9 一样） */
        fun screen(w: Float, h: Float): Projection {
            McCompat.pushScreenProjection(Matrix4f().setOrtho(0f, w, h, 0f, 1000f, 3000f))
            McCompat.pushIdentityModelView()
            return Projection()
        }
    }
}

// ---------------------------------------------------------------- 贴图

/** 交给原版 TextureManager 管理的一张贴图。[glId] 每次现取（原版的贴图对象会延迟创建 / 重建） */
class ModernTexture(private val glId: () -> Int, internal val release: () -> Unit) : Texture {
    fun bind() {
        Gl.bindTexture(glId())
    }
}

object ModernTextures : Textures {
    override fun upload(name: String, image: BufferedImage): Texture {
        val w = image.width
        val h = image.height
        // 原版的 NativeImage 用的是 ABGR
        val abgr = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = image.getRGB(x, y)
                abgr[y * w + x] = (argb and 0xFF00FF00.toInt()) or ((argb shr 16) and 0xFF) or ((argb and 0xFF) shl 16)
            }
        }
        return McCompat.createTexture(name, w, h, abgr)
    }

    override fun delete(texture: Texture) {
        (texture as ModernTexture).release()
    }
}

// ---------------------------------------------------------------- 歌词文字

object ModernText : TextCanvas {
    override fun push() = Fixed.push()
    override fun pop() = Fixed.pop()
    override fun translate(x: Float, y: Float) {
        Fixed.pose.translate(x, y, 0f)
    }
    override fun rotate(degrees: Float) {
        Fixed.pose.rotateZ(Math.toRadians(degrees.toDouble()).toFloat())
    }
    override fun scale(x: Float, y: Float) {
        Fixed.pose.scale(x, y, 1f)
    }

    override fun color(r: Float, g: Float, b: Float, a: Float) = Fixed.color(r, g, b, a)

    override fun lyricFont(file: java.io.File?) = CjkFont.setLyricFont(file)

    override fun smoothText(text: String, x: Float, baseline: Float, size: Float, style: Int) =
        CjkFont.draw(text, x, baseline, size, style)

    override fun smoothWidth(text: String, size: Float, style: Int): Float = CjkFont.width(text, size, style)

    override fun beginSmooth() {
        Gl.activeTexture(GL13.GL_TEXTURE0)
    }

    override fun endSmooth() {
        Fixed.color(1f, 1f, 1f, 1f)
    }

    override fun pixelText(text: String, x: Float, y: Float, argb: Int, shadow: Boolean) {
        if (shadow) {
            // 阴影单独先画一批：原版把阴影和字放在同一批里，半透明的东西会按离镜头的远近重新排序，
            // 歌词的阴影和字几乎一样远，排序结果会随视角变化，有时阴影就跑到字上面了。
            // 阴影的颜色和位置照原版算（unicode 字形的阴影只偏 0.5）
            val shadowColor = ((argb and 0xFCFCFC) shr 2) or (argb and 0xFF000000.toInt())
            val offset = if (text.all { it.code < 0x2E80 }) 1f else 0.5f
            ModernGfx.vanillaText(text, x + offset, y + offset, shadowColor, false, Fixed.pose)
        }
        ModernGfx.vanillaText(text, x, y, argb, false, Fixed.pose)
        reapply()
    }

    override fun pixelWidth(text: String): Float = Minecraft.getInstance().font.width(text).toFloat()

    override fun pixelCharWidth(c: Char): Float = Minecraft.getInstance().font.width(c.toString()).toFloat()

    /** 原版字体画完会改掉混合 / 深度这些状态，按当前在画的地方重新设好 */
    internal var reapply: () -> Unit = {}

    private var screenState: GlState? = null
    private var screenProjection: Projection? = null

    override fun beginScreen() {
        screenState = GlState()
        val window = Minecraft.getInstance().window
        screenProjection = Projection.screen(window.width.toFloat(), window.height.toFloat())
        Fixed.push()
        Fixed.pose.identity().translate(0f, 0f, -2000f)
        ModernGfx.textState()
        reapply = { ModernGfx.textState() }
    }

    override fun endScreen() {
        Fixed.pop()
        Fixed.color(1f, 1f, 1f, 1f)
        screenProjection?.restore()
        screenProjection = null
        screenState?.restore()
        screenState = null
    }
}

// ---------------------------------------------------------------- 世界里的歌词

object ModernWorld : WorldView {
    /** 世界渲染结束时的模型矩阵（含镜头转向），由 Fabric 事件每帧设好 */
    internal val basePose = Matrix4f()

    private val camera get() = McCompat.cameraPosition()
    override val cameraX: Double get() = camera.x
    override val cameraY: Double get() = camera.y
    override val cameraZ: Double get() = camera.z

    private fun worldState() {
        Gl.blend(true)
        Gl.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
        Gl.depthTest(false)
        Gl.depthMask(false)
        Gl.cull(false)
        Gl.activeTexture(GL13.GL_TEXTURE0)
    }

    override fun begin() {
        Fixed.reset(basePose)
        worldState()
        ModernText.reapply = { worldState() }
    }

    override fun push(rx: Double, ry: Double, rz: Double, yaw: Float, pitch: Float, scale: Float) {
        Fixed.push()
        Fixed.pose.translate(rx.toFloat(), ry.toFloat(), rz.toFloat())
        Fixed.pose.rotateY(Math.toRadians(-yaw.toDouble()).toFloat())
        Fixed.pose.rotateX(Math.toRadians(pitch.toDouble()).toFloat())
        Fixed.pose.scale(-scale, -scale, scale)
    }

    override fun pop() = Fixed.pop()

    override fun resync() {
        Fixed.color(1f, 1f, 1f, 1f)
        worldState()
    }
}

// ---------------------------------------------------------------- 左上角 HUD

object ModernHud : HudCanvas {
    private var state: GlState? = null
    private var projection: Projection? = null

    private fun hudState() {
        Gl.blend(true)
        Gl.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
        Gl.depthTest(false)
        Gl.cull(false)
        Gl.activeTexture(GL13.GL_TEXTURE0)
    }

    override fun begin() {
        state = GlState()
        val window = Minecraft.getInstance().window
        projection = Projection.screen(window.guiScaledWidth.toFloat(), window.guiScaledHeight.toFloat())
        Fixed.push()
        Fixed.pose.identity().translate(0f, 0f, -2000f)
        hudState()
        ModernText.reapply = { hudState() }
    }

    override fun end() {
        Fixed.pop()
        Fixed.color(1f, 1f, 1f, 1f)
        projection?.restore()
        projection = null
        state?.restore()
        state = null
    }

    override fun rect(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) {
        val a = (argb ushr 24 and 255) / 255f
        val r = (argb shr 16 and 255) / 255f
        val g = (argb shr 8 and 255) / 255f
        val b = (argb and 255) / 255f
        Fixed.color(r, g, b, a)
        val l = minOf(x1, x2).toFloat()
        val t = minOf(y1, y2).toFloat()
        val rr = maxOf(x1, x2).toFloat()
        val bb = maxOf(y1, y2).toFloat()
        Imm.begin(textured = false)
        Imm.vertex(l, t)
        Imm.vertex(l, bb)
        Imm.vertex(rr, bb)
        Imm.vertex(rr, t)
        Imm.endFixed()
        Fixed.color(1f, 1f, 1f, 1f)
    }

    override fun texture(texture: Texture, x: Int, y: Int, width: Int, height: Int, alpha: Float) {
        (texture as ModernTexture).bind()
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        Fixed.color(1f, 1f, 1f, alpha)
        val l = x.toFloat()
        val t = y.toFloat()
        val r = (x + width).toFloat()
        val b = (y + height).toFloat()
        Imm.begin()
        Imm.tex(0f, 0f); Imm.vertex(l, t)
        Imm.tex(0f, 1f); Imm.vertex(l, b)
        Imm.tex(1f, 1f); Imm.vertex(r, b)
        Imm.tex(1f, 0f); Imm.vertex(r, t)
        Imm.endFixed()
        Fixed.color(1f, 1f, 1f, 1f)
    }

    override fun text(text: String, x: Float, y: Float, argb: Int, shadow: Boolean) {
        ModernGfx.vanillaText(text, x, y, argb, shadow, Fixed.pose)
        hudState()
    }
}
