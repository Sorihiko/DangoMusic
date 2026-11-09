package me.sorihiko.dango.ui.glass

import me.sorihiko.dango.modern.Gl
import me.sorihiko.dango.modern.GlState
import me.sorihiko.dango.modern.Imm
import me.sorihiko.dango.modern.ModernGl
import me.sorihiko.dango.shader.impls.QuadShader
import me.sorihiko.dango.shader.impls.ui.GlassBlurShader

import net.minecraft.client.Minecraft
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

import java.nio.ByteBuffer

/**
 * 液态玻璃的"背后世界"：每帧把当前画面拷贝下来，生成两级模糊纹理。
 *  - soft ：1/4 分辨率轻度模糊（清透玻璃、折射边缘）
 *  - frost：1/8 分辨率重度模糊（磨砂玻璃）
 * 必须在绘制菜单之前调用 [capture]，这样菜单自身不会出现在背景里。
 */
class GlassBackdrop {
    private val quadShader = ModernGl.shaderManager.get<QuadShader>()
    private val blurShader = ModernGl.shaderManager.get<GlassBlurShader>()

    private var captureTexture = -1
    private var width = 0
    private var height = 0

    private val half = GlassTarget()
    private val softA = GlassTarget()
    private val softB = GlassTarget()
    private val frostA = GlassTarget()
    private val frostB = GlassTarget()

    val softTexture: Int get() = softA.texture
    val frostTexture: Int get() = frostA.texture

    var ready = false
        private set

    /** [fromDrawTarget] = 截正在画的那一层（画到一半时重新截背景用），否则截当前读取的画面 */
    fun capture(fromDrawTarget: Boolean = false) {
        val window = Minecraft.getInstance().window
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return
        me.sorihiko.dango.modern.OffscreenHints.glassUsed = true
        me.sorihiko.dango.modern.Imm.flush()
        me.sorihiko.dango.modern.Perf.begin("glass")
        try { captureNow(w, h, fromDrawTarget) } finally { me.sorihiko.dango.modern.Perf.end("glass") }
    }

    private fun captureNow(w: Int, h: Int, fromDrawTarget: Boolean) {

        // 高版本没有 glPushAttrib：自己记下状态，最后原样恢复（帧缓冲、视口、混合、贴图……）
        val state = GlState()
        val sourceFbo = when {
            fromDrawTarget -> GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
            mainSource > 0 -> mainSource
            else -> GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        }
        try {
            // 先切到 0 号单元再建贴图：不然新贴图会绑到光影正在用的单元上（#53）
            Gl.activeTexture(GL13.GL_TEXTURE0)
            if (w != width || h != height || captureTexture == -1) resize(w, h)

            // resize 创建模糊目标时会改变绑定；采样仍须来自原来的画面。
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFbo)

            Gl.activeTexture(GL13.GL_TEXTURE0)
            Gl.bindTexture(captureTexture)
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)

            Gl.blend(false)
            Gl.depthTest(false)
            Gl.scissor(false)
            Gl.cull(false)
            Gl.colorMask(true, true, true, true)

            // 逐级 2x 降采样（双线性正好取 2x2 平均，画面移动时不闪）
            blit(captureTexture, half)
            blit(half.texture, softA)
            blur(softA, softB, 1f, 2)

            blit(softA.texture, frostA)
            blur(frostA, frostB, 1.6f, 3)

            ready = true
        } finally {
            state.restore()
        }
    }

    private fun resize(w: Int, h: Int) {
        width = w
        height = h

        if (captureTexture != -1) Gl.deleteTexture(captureTexture)
        captureTexture = GlassTarget.createTexture(w, h)

        half.ensure(w / 2, h / 2)
        softA.ensure(w / 4, h / 4)
        softB.ensure(w / 4, h / 4)
        frostA.ensure(w / 8, h / 8)
        frostB.ensure(w / 8, h / 8)
    }

    private fun blit(source: Int, target: GlassTarget) {
        target.bind()
        quadShader.bind()
        quadShader.setup()
        Gl.bindTexture(source)
        fullscreenQuad()
    }

    /** 可分离高斯：A -> B（横向）-> A（纵向），结果留在 A */
    private fun blur(a: GlassTarget, b: GlassTarget, spread: Float, iterations: Int) {
        blurShader.bind()
        repeat(iterations) {
            b.bind()
            blurShader.setup(spread / a.width, 0f)
            Gl.bindTexture(a.texture)
            fullscreenQuad()

            a.bind()
            blurShader.setup(0f, spread / b.height)
            Gl.bindTexture(b.texture)
            fullscreenQuad()
        }
    }

    private fun fullscreenQuad() {
        Imm.begin()
        Imm.tex(0f, 0f); Imm.vertex(-1f, -1f)
        Imm.tex(1f, 0f); Imm.vertex(1f, -1f)
        Imm.tex(1f, 1f); Imm.vertex(1f, 1f)
        Imm.tex(0f, 1f); Imm.vertex(-1f, 1f)
        Imm.end()
    }

    fun delete() {
        if (captureTexture != -1) Gl.deleteTexture(captureTexture)
        captureTexture = -1
        listOf(half, softA, softB, frostA, frostB).forEach { it.delete() }
        width = 0
        height = 0
        ready = false
    }

    companion object {
        /**
         * 画在图层上时，背景该从哪张画面截（1.21.11：主画面）。
         * 不能靠"当前读取的画面"：画歌词时原版可能把它换掉，截到的就成了刚画好的歌词，灵动岛会闪一下（#44）。
         * 0 = 没指定，照旧截当前读取的画面。
         */
        @JvmField var mainSource = 0
    }
}

/** 线性过滤 + 边缘钳制的颜色 FBO（RenderTarget 是 NEAREST，不适合降采样） */
class GlassTarget {
    var fbo = -1
        private set
    var texture = -1
        private set
    var width = 0
        private set
    var height = 0
        private set

    fun ensure(w: Int, h: Int) {
        val nw = w.coerceAtLeast(1)
        val nh = h.coerceAtLeast(1)
        if (nw == width && nh == height && fbo != -1) return
        delete()
        width = nw
        height = nh

        texture = createTexture(nw, nh)
        fbo = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0
        )
        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Glass framebuffer create failed: $status")
        }
    }

    fun bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL11.glViewport(0, 0, width, height)
    }

    fun delete() {
        if (fbo != -1) GL30.glDeleteFramebuffers(fbo)
        if (texture != -1) Gl.deleteTexture(texture)
        fbo = -1
        texture = -1
        width = 0
        height = 0
    }

    companion object {
        fun createTexture(w: Int, h: Int): Int {
            val id = Gl.genTexture()
            Gl.withDefaultUnpack {
                GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?
                )
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            return id
        }
    }
}
