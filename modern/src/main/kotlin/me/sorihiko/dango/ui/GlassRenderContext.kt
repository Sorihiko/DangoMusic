package me.sorihiko.dango.ui

import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.modern.Gl
import me.sorihiko.dango.modern.GlState
import me.sorihiko.dango.modern.Imm
import me.sorihiko.dango.modern.ModernGl
import me.sorihiko.dango.modern.ModernTexture
import me.sorihiko.dango.shader.impls.ui.FontShader
import me.sorihiko.dango.shader.impls.ui.GlassShader
import me.sorihiko.dango.shader.impls.ui.ImageShader
import me.sorihiko.dango.shader.impls.ui.ShapeShader
import me.sorihiko.dango.ui.glass.GlassBackdrop
import me.sorihiko.dango.utils.Color

import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.joml.Vector4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13

import kotlin.math.abs

/** 高版本的液态玻璃画布：OpenGL 3.2 core + 自己的着色器（和 1.8.9 版画法完全一样） */
class GlassRenderContext : GuiCanvas {
    private val shapeShader = ModernGl.shaderManager.get<ShapeShader>()
    private val fontShader = ModernGl.shaderManager.get<FontShader>()
    private val imageShader = ModernGl.shaderManager.get<ImageShader>()
    private val glassShader = ModernGl.shaderManager.get<GlassShader>()

    /** 每帧在 [begin] 里抓取；没抓到时 drawGlass 退化为普通圆角矩形 */
    private var backdrop: GlassBackdrop? = null
    private var snapshot: GlState? = null

    private val clipStack = ArrayDeque<LayoutBounds>()
    private val opacityStack = ArrayDeque<Float>()
    private val transformStack = ArrayDeque<Matrix4f>()

    private var _mouseX: Int = 0
    private var _mouseY: Int = 0

    override fun begin(captureBackdrop: Boolean, mouseX: Int, mouseY: Int) {
        snapshot = GlState()
        val b = backdrop ?: GlassBackdrop().also { backdrop = it }
        if (captureBackdrop) b.capture()

        Gl.alphaBlend()
        Gl.depthTest(false)
        Gl.cull(false)
        Gl.activeTexture(GL13.GL_TEXTURE0)
        _mouseX = mouseX
        _mouseY = mouseY
    }

    override fun recaptureBackdrop() {
        // 截正在画的这一层（26.x 和 Vulkan 时播放器画在自己的图层上，不一定是当前读取的画面）
        backdrop?.capture(fromDrawTarget = true)
    }

    override fun end() {
        Gl.scissor(false)
        snapshot?.restore()
        snapshot = null
    }

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        Gl.scissor(true)
        Gl.scissorBox(x, y, width, height)
    }

    override fun noScissor() {
        Gl.scissor(false)
    }

    private fun screenSize(): Size {
        val w = Minecraft.getInstance().window
        return Size(w.width.toFloat(), w.height.toFloat())
    }

    override fun getMouseX(): Int {
        return _mouseX
    }

    override fun getMouseY(): Int {
        return _mouseY
    }

    override fun isMouseOver(bounds: LayoutBounds): Boolean {
        return bounds.contains(_mouseX, _mouseY)
    }

    override fun drawShape(bounds: LayoutBounds, style: ShapeStyle) {
        shapeShader.bind()
        shapeShader.setup(
            getTransform(),
            screenSize(),
            bounds,
            style,
            getOpacity()
        )
        val renderPadding =
            style.borderWidth + style.shadow.size + maxOf(abs(style.shadow.offset.x), abs(style.shadow.offset.y))

        val x1 = bounds.x - renderPadding
        val y1 = bounds.y - renderPadding
        val x2 = bounds.x + bounds.width + renderPadding
        val y2 = bounds.y + bounds.height + renderPadding

        Imm.begin()

        Imm.vertex(x1, y1)
        Imm.vertex(x1, y2)
        Imm.vertex(x2, y2)
        Imm.vertex(x2, y1)

        Imm.end()

        shapeShader.unbind()
    }

    override fun drawText(text: String, x: Float, y: Float, style: FontStyle) {
        val uiFont = ModernGl.font(style.font)

        fontShader.bind()
        fontShader.setup(
            getTransform(),
            screenSize(),
            style.color,
            getOpacity()
        )
        Gl.activeTexture(GL13.GL_TEXTURE0)
        Gl.bindTexture(uiFont.textureId)

        val scale = style.fontSize / uiFont.fontSize
        var curX = x
        Imm.begin()
        for (c in text) {
            val glyph = uiFont.getGlyph(c) ?: continue

            val x0 = curX + glyph.bearingX * scale
            val y0 = y - glyph.bearingY * scale
            val x1 = x0 + glyph.width * scale
            val y1 = y0 + glyph.height * scale

            Imm.tex(glyph.u0, glyph.v0)
            Imm.vertex(x0, y0)
            Imm.tex(glyph.u0, glyph.v1)
            Imm.vertex(x0, y1)
            Imm.tex(glyph.u1, glyph.v1)
            Imm.vertex(x1, y1)
            Imm.tex(glyph.u1, glyph.v0)
            Imm.vertex(x1, y0)

            curX += glyph.advance * scale
        }

        Imm.end()
        fontShader.unbind()
    }

    override fun drawImage(bounds: LayoutBounds, image: Texture) {
        imageShader.bind()
        imageShader.setup(
            getTransform(),
            screenSize(),
            getOpacity()
        )
        Gl.activeTexture(GL13.GL_TEXTURE0)
        (image as ModernTexture).bind()

        Imm.begin()
        Imm.tex(0f, 0f)
        Imm.vertex(bounds.x, bounds.y)
        Imm.tex(0f, 1f)
        Imm.vertex(bounds.x, bounds.bottom)
        Imm.tex(1f, 1f)
        Imm.vertex(bounds.right, bounds.bottom)
        Imm.tex(1f, 0f)
        Imm.vertex(bounds.right, bounds.y)
        Imm.end()

        imageShader.unbind()
    }

    override fun drawGlass(bounds: LayoutBounds, style: GlassStyle) {
        if (style.shadow.size > 0f) {
            drawShape(bounds, ShapeStyle(Color.TRANSPARENT, style.radius, shadow = style.shadow))
        }

        val backdrop = this.backdrop
        if (backdrop == null || !backdrop.ready) {
            drawShape(bounds, ShapeStyle(style.fallback, style.radius, 1f, Color(1f, 1f, 1f, 0.10f)))
            return
        }

        glassShader.bind()
        glassShader.setup(
            getTransform(),
            screenSize(),
            bounds,
            style,
            getOpacity()
        )

        Gl.activeTexture(GL13.GL_TEXTURE1)
        Gl.bindTexture(backdrop.frostTexture)
        Gl.activeTexture(GL13.GL_TEXTURE0)
        Gl.bindTexture(backdrop.softTexture)

        val x1 = bounds.x - 1f
        val y1 = bounds.y - 1f
        val x2 = bounds.right + 1f
        val y2 = bounds.bottom + 1f

        Imm.begin()
        Imm.vertex(x1, y1)
        Imm.vertex(x1, y2)
        Imm.vertex(x2, y2)
        Imm.vertex(x2, y1)
        Imm.end()

        Gl.activeTexture(GL13.GL_TEXTURE1)
        Gl.bindTexture(0)
        Gl.activeTexture(GL13.GL_TEXTURE0)

        glassShader.unbind()
    }

    override fun pushClip(bounds: LayoutBounds) {
        val transformed = transformBounds(bounds)

        val clip = if (clipStack.isEmpty()) {
            transformed
        } else {
            clipStack.last().intersect(transformed)
        }

        clipStack.addLast(clip)

        Gl.scissor(true)
        applyScissor(clip)
    }

    override fun popClip() {
        clipStack.removeLast()

        if (clipStack.isEmpty()) {
            Gl.scissor(false)
        } else {
            applyScissor(clipStack.last())
        }
    }

    override fun pushOpacity(value: Float) {
        val value = value.coerceIn(0f, 1f)
        opacityStack.addLast(getOpacity() * value)
    }

    override fun popOpacity() {
        opacityStack.removeLastOrNull()
    }

    override fun getOpacity(): Float {
        return opacityStack.lastOrNull() ?: 1f
    }

    private fun pushTransform(transform: Matrix4f) {
        val result = Matrix4f(getTransform()).mul(transform)
        transformStack.addLast(result)
    }

    override fun popTransform() {
        transformStack.removeLast()
    }

    private fun getTransform(): Matrix4f {
        return transformStack.lastOrNull() ?: Matrix4f()
    }

    override fun pushTranslate(x: Float, y: Float, z: Float) {
        pushTransform(Matrix4f().translation(x, y, z))
    }

    override fun pushScale(x: Float, y: Float, z: Float) {
        pushTransform(Matrix4f().scaling(x, y, z))
    }

    override fun pushRotate(angle: Float, x: Float, y: Float, z: Float) {
        pushTransform(Matrix4f().rotation(Math.toRadians(angle.toDouble()).toFloat(), x, y, z))
    }

    private fun applyScissor(bounds: LayoutBounds) {
        Gl.scissorBox(
            bounds.x.toInt(),
            (Minecraft.getInstance().window.height - bounds.y - bounds.height).toInt(),
            bounds.width.toInt(),
            bounds.height.toInt()
        )
    }

    // 同时变换两个角，缩放变换下裁剪区域也正确
    private fun transformBounds(bounds: LayoutBounds): LayoutBounds {
        val matrix = getTransform()
        val a = Vector4f(bounds.x, bounds.y, 0f, 1f)
        val b = Vector4f(bounds.right, bounds.bottom, 0f, 1f)
        matrix.transform(a)
        matrix.transform(b)
        val left = minOf(a.x, b.x)
        val top = minOf(a.y, b.y)
        return LayoutBounds(left, top, maxOf(a.x, b.x) - left, maxOf(a.y, b.y) - top)
    }
}