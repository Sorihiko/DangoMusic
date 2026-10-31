package me.sorihiko.dango.ui

import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.legacy.LegacyGl
import me.sorihiko.dango.legacy.LegacyTexture
import me.sorihiko.dango.legacy.mc
import me.sorihiko.dango.shader.impls.ui.FontShader
import me.sorihiko.dango.shader.impls.ui.GlassShader
import me.sorihiko.dango.shader.impls.ui.ImageShader
import me.sorihiko.dango.shader.impls.ui.ShapeShader
import me.sorihiko.dango.ui.glass.GlassBackdrop
import me.sorihiko.dango.utils.Color
import me.sorihiko.dango.utils.render.GlStateSnapshot

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.util.vector.Matrix4f
import org.lwjgl.util.vector.Vector3f
import org.lwjgl.util.vector.Vector4f

import kotlin.math.abs

/** 1.12.2 的液态玻璃画布：直接用 OpenGL 2 + 自己的着色器画 */
class GlassRenderContext : GuiCanvas {
    private val shapeShader = LegacyGl.shaderManager.get<ShapeShader>()
    private val fontShader = LegacyGl.shaderManager.get<FontShader>()
    private val imageShader = LegacyGl.shaderManager.get<ImageShader>()
    private val glassShader = LegacyGl.shaderManager.get<GlassShader>()

    /** 每帧在 [begin] 里抓取；没抓到时 drawGlass 退化为普通圆角矩形 */
    private var backdrop: GlassBackdrop? = null
    private var snapshot: GlStateSnapshot? = null

    private val clipStack = ArrayDeque<LayoutBounds>()
    private val opacityStack = ArrayDeque<Float>()
    private val transformStack = ArrayDeque<Matrix4f>()

    private var _mouseX: Int = 0
    private var _mouseY: Int = 0

    override fun begin(captureBackdrop: Boolean, mouseX: Int, mouseY: Int) {
        snapshot = GlStateSnapshot()
        GL20.glUseProgram(0)
        val b = backdrop ?: GlassBackdrop().also { backdrop = it }
        if (captureBackdrop) b.capture()

        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_CULL_FACE)
        _mouseX = mouseX
        _mouseY = mouseY
    }

    override fun end() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        snapshot?.restore()
        snapshot = null
    }

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(x, y, width, height)
    }

    override fun noScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
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
            Size(
                mc.displayWidth.toFloat(),
                mc.displayHeight.toFloat()
            ),
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

        GL11.glBegin(GL11.GL_QUADS)

        GL11.glVertex2f(x1, y1)
        GL11.glVertex2f(x1, y2)
        GL11.glVertex2f(x2, y2)
        GL11.glVertex2f(x2, y1)

        GL11.glEnd()

        shapeShader.unbind()
    }

    override fun drawText(text: String, x: Float, y: Float, style: FontStyle) {
        val uiFont = LegacyGl.font(style.font)

        fontShader.bind()
        fontShader.setup(
            getTransform(),
            Size(
                mc.displayWidth.toFloat(),
                mc.displayHeight.toFloat()
            ),
            style.color,
            getOpacity()
        )
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(
            GL11.GL_TEXTURE_2D,
            uiFont.textureId
        )

        val scale = style.fontSize / uiFont.fontSize
        var curX = x
        GL11.glBegin(GL11.GL_QUADS)
        for (c in text) {
            val glyph = uiFont.getGlyph(c) ?: continue

            val x0 = curX + glyph.bearingX * scale
            val y0 = y - glyph.bearingY * scale
            val x1 = x0 + glyph.width * scale
            val y1 = y0 + glyph.height * scale

            GL11.glTexCoord2f(glyph.u0, glyph.v0)
            GL11.glVertex2f(x0, y0)
            GL11.glTexCoord2f(glyph.u0, glyph.v1)
            GL11.glVertex2f(x0, y1)
            GL11.glTexCoord2f(glyph.u1, glyph.v1)
            GL11.glVertex2f(x1, y1)
            GL11.glTexCoord2f(glyph.u1, glyph.v0)
            GL11.glVertex2f(x1, y0)

            curX += glyph.advance * scale
        }

        GL11.glEnd()
        fontShader.unbind()
    }

    override fun drawImage(bounds: LayoutBounds, image: Texture) {
        imageShader.bind()
        imageShader.setup(
            getTransform(),
            Size(
                mc.displayWidth.toFloat(),
                mc.displayHeight.toFloat()
            ),
            getOpacity()
        )
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        (image as LegacyTexture).bind()

        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(0f, 0f)
        GL11.glVertex2f(bounds.x, bounds.y)
        GL11.glTexCoord2f(0f, 1f)
        GL11.glVertex2f(bounds.x, bounds.bottom)
        GL11.glTexCoord2f(1f, 1f)
        GL11.glVertex2f(bounds.right, bounds.bottom)
        GL11.glTexCoord2f(1f, 0f)
        GL11.glVertex2f(bounds.right, bounds.y)
        GL11.glEnd()

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
            Size(
                mc.displayWidth.toFloat(),
                mc.displayHeight.toFloat()
            ),
            bounds,
            style,
            getOpacity()
        )

        GL13.glActiveTexture(GL13.GL_TEXTURE1)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, backdrop.frostTexture)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, backdrop.softTexture)

        val x1 = bounds.x - 1f
        val y1 = bounds.y - 1f
        val x2 = bounds.right + 1f
        val y2 = bounds.bottom + 1f

        GL11.glBegin(GL11.GL_QUADS)
        GL11.glVertex2f(x1, y1)
        GL11.glVertex2f(x1, y2)
        GL11.glVertex2f(x2, y2)
        GL11.glVertex2f(x2, y1)
        GL11.glEnd()

        GL13.glActiveTexture(GL13.GL_TEXTURE1)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)

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

        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        applyScissor(clip)
    }

    override fun popClip() {
        clipStack.removeLast()

        if (clipStack.isEmpty()) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
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
        val result = Matrix4f.mul(getTransform(), transform, null)
        transformStack.addLast(result)
    }

    override fun popTransform() {
        transformStack.removeLast()
    }

    private fun getTransform(): Matrix4f {
        return transformStack.lastOrNull() ?: Matrix4f()
    }

    override fun pushTranslate(x: Float, y: Float, z: Float) {
        val matrix = Matrix4f()
        matrix.translate(Vector3f(x, y, z))
        pushTransform(matrix)
    }

    override fun pushScale(x: Float, y: Float, z: Float) {
        val matrix = Matrix4f()
        matrix.scale(Vector3f(x, y, z))
        pushTransform(matrix)
    }

    override fun pushRotate(angle: Float, x: Float, y: Float, z: Float) {
        val matrix = Matrix4f()
        matrix.rotate(
            Math.toRadians(angle.toDouble()).toFloat(),
            Vector3f(x, y, z)
        )
        pushTransform(matrix)
    }

    private fun applyScissor(bounds: LayoutBounds) {
        GL11.glScissor(
            bounds.x.toInt(),
            (mc.displayHeight - bounds.y - bounds.height).toInt(),
            bounds.width.toInt(),
            bounds.height.toInt()
        )
    }

    // 同时变换两个角，缩放变换下裁剪区域也正确
    private fun transformBounds(bounds: LayoutBounds): LayoutBounds {
        val matrix = getTransform()
        val a = Vector4f(bounds.x, bounds.y, 0f, 1f)
        val b = Vector4f(bounds.right, bounds.bottom, 0f, 1f)
        Matrix4f.transform(matrix, a, a)
        Matrix4f.transform(matrix, b, b)
        val left = minOf(a.x, b.x)
        val top = minOf(a.y, b.y)
        return LayoutBounds(left, top, maxOf(a.x, b.x) - left, maxOf(a.y, b.y) - top)
    }
}

