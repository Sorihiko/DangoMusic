package me.sorihiko.dango.lyrics

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.gfx.GlowPass
import me.sorihiko.dango.legacy.mc
import me.sorihiko.dango.utils.render.GlCaps

import net.minecraft.client.renderer.OpenGlHelper
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GLContext

import java.nio.ByteBuffer
import kotlin.math.max

/**
 * 类似 AE Deep Glow 的发光：
 *   1. 把要发光的东西（歌词）单独画到一张离屏画布（mask）上
 *   2. 逐级缩小到 1/2、1/4 … 1/128（每级做一次模糊）
 *   3. 再从最小一级逐级放大叠回来 —— 小级别给出很大很柔的光晕，大级别给出贴着字的亮边，
 *      叠在一起就是"中心很亮、往外慢慢衰减"的物理感光晕
 *   4. 用指数色调映射叠加到画面上：越亮的地方越接近白色（字芯发白），边缘是饱和的颜色
 *
 * 用法：begin() → 画要发光的内容 → end(...)，之后再正常画一遍清晰的本体。
 * 只能在渲染线程调用；画布状态会自己保存 / 恢复当前帧缓冲和视口。
 */
class DeepGlow : GlowPass {

    private companion object {
        const val LEVELS = 7

        const val VERT = """
#version 120
void main() {
    gl_TexCoord[0] = gl_MultiTexCoord0;
    gl_Position = gl_Vertex;
}
"""

        // 缩小：中心 + 四个角（双重 Kawase 下采样），顺便做一次模糊
        const val DOWN = """
#version 120
uniform sampler2D tex;
uniform vec2 texel;
void main() {
    vec2 uv = gl_TexCoord[0].xy;
    vec4 c = texture2D(tex, uv) * 4.0;
    c += texture2D(tex, uv - texel);
    c += texture2D(tex, uv + texel);
    c += texture2D(tex, uv + vec2(texel.x, -texel.y));
    c += texture2D(tex, uv + vec2(-texel.x, texel.y));
    gl_FragColor = c / 8.0;
}
"""

        // 放大：8 点帐篷滤波，乘上"扩散"系数后叠加到上一级
        const val UP = """
#version 120
uniform sampler2D tex;
uniform vec2 texel;
uniform float intensity;
void main() {
    vec2 uv = gl_TexCoord[0].xy;
    vec4 s = texture2D(tex, uv + vec2(-2.0 * texel.x, 0.0));
    s += texture2D(tex, uv + vec2(2.0 * texel.x, 0.0));
    s += texture2D(tex, uv + vec2(0.0, -2.0 * texel.y));
    s += texture2D(tex, uv + vec2(0.0, 2.0 * texel.y));
    s += texture2D(tex, uv + vec2(-texel.x, texel.y)) * 2.0;
    s += texture2D(tex, uv + vec2(texel.x, texel.y)) * 2.0;
    s += texture2D(tex, uv + vec2(texel.x, -texel.y)) * 2.0;
    s += texture2D(tex, uv + vec2(-texel.x, -texel.y)) * 2.0;
    gl_FragColor = s / 12.0 * intensity;
}
"""

        // 合成：指数色调映射（亮处趋向白色），加一点抖动避免色带
        const val COMPOSITE = """
#version 120
uniform sampler2D tex;
uniform vec3 tint;
uniform float exposure;
float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }
void main() {
    vec4 g = texture2D(tex, gl_TexCoord[0].xy);
    vec3 energy = g.rgb * tint * exposure;
    vec3 c = vec3(1.0) - exp(-energy);
    // 按概率进位到 1/255 的整数倍：光晕最外圈以细颗粒慢慢淡出，不会在暗处露出一条边
    c = floor(c * 255.0 + rand(gl_FragCoord.xy)) / 255.0;
    gl_FragColor = vec4(c, 1.0);
}
"""
    }

    private var progDown = 0
    private var progUp = 0
    private var progComp = 0
    private var failed = false

    private var width = 0
    private var height = 0
    private var maskTex = 0
    private var maskFbo = 0
    private val levelTex = IntArray(LEVELS)
    private val levelFbo = IntArray(LEVELS)
    private val levelW = IntArray(LEVELS)
    private val levelH = IntArray(LEVELS)

    private var savedFbo = 0
    private val savedViewport = BufferUtils.createIntBuffer(16)
    private val savedClear = BufferUtils.createFloatBuffer(16)
    private var active = false

    override val available: Boolean get() = !failed

    /** 出错后整局关掉发光（日志里会写原因），保证歌词本身照常显示 */
    override fun disable(reason: Throwable) {
        if (!failed) DangoLogger.warn("DeepGlow disabled after error: {}", reason.toString())
        failed = true
        abort()
    }

    // ---------------------------------------------------------------- 初始化

    private fun compile(type: Int, src: String): Int {
        val id = GL20.glCreateShader(type)
        GL20.glShaderSource(id, src)
        GL20.glCompileShader(id)
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetShaderInfoLog(id, 4096)
            GL20.glDeleteShader(id)
            error("DeepGlow shader compile failed: $log")
        }
        return id
    }

    private fun program(frag: String): Int {
        val vs = compile(GL20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GL20.GL_FRAGMENT_SHADER, frag)
        val p = GL20.glCreateProgram()
        GL20.glAttachShader(p, vs)
        GL20.glAttachShader(p, fs)
        GL20.glLinkProgram(p)
        GL20.glDeleteShader(vs)
        GL20.glDeleteShader(fs)
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            error("DeepGlow shader link failed: " + GL20.glGetProgramInfoLog(p, 4096))
        }
        return p
    }

    private fun ensurePrograms(): Boolean {
        if (failed) return false
        if (progComp != 0) return true
        return try {
            progDown = program(DOWN)
            progUp = program(UP)
            progComp = program(COMPOSITE)
            true
        } catch (e: Throwable) {
            DangoLogger.warn("DeepGlow disabled: {}", e.toString())
            failed = true
            false
        }
    }

    /** 半精度浮点贴图光晕更细腻（没有色带）；显卡不支持就退回 8 位 */
    private val floatFormat: Boolean by lazy {
        val caps = GLContext.getCapabilities()
        caps.OpenGL30 || caps.GL_ARB_texture_float
    }

    private fun newTexture(w: Int, h: Int, hdr: Boolean): Int {
        val id = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        val internal = if (hdr && floatFormat) 0x881A /* GL_RGBA16F */ else GL11.GL_RGBA8
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
        return id
    }

    private fun newFbo(tex: Int): Int {
        val fbo = OpenGlHelper.glGenFramebuffers()
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo)
        OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0)
        return fbo
    }

    private fun release() {
        if (maskTex != 0) GL11.glDeleteTextures(maskTex)
        if (maskFbo != 0) OpenGlHelper.glDeleteFramebuffers(maskFbo)
        for (i in 0 until LEVELS) {
            if (levelTex[i] != 0) GL11.glDeleteTextures(levelTex[i])
            if (levelFbo[i] != 0) OpenGlHelper.glDeleteFramebuffers(levelFbo[i])
            levelTex[i] = 0
            levelFbo[i] = 0
        }
        maskTex = 0
        maskFbo = 0
    }

    private fun ensureTargets(w: Int, h: Int) {
        if (w == width && h == height && maskFbo != 0) return
        release()
        width = w
        height = h
        maskTex = newTexture(w, h, false)
        maskFbo = newFbo(maskTex)
        var lw = w
        var lh = h
        for (i in 0 until LEVELS) {
            lw = max(1, lw / 2)
            lh = max(1, lh / 2)
            levelW[i] = lw
            levelH[i] = lh
            levelTex[i] = newTexture(lw, lh, true)
            levelFbo[i] = newFbo(levelTex[i])
        }
    }

    // ---------------------------------------------------------------- 使用

    /** 开始往发光画布上画。返回 false 表示用不了（着色器编译失败等），这时直接跳过发光 */
    override fun begin(): Boolean {
        if (!OpenGlHelper.isFramebufferEnabled() || !ensurePrograms()) return false
        val w = mc.displayWidth
        val h = mc.displayHeight
        if (w <= 0 || h <= 0) return false

        savedFbo = GL11.glGetInteger(0x8CA6 /* GL_FRAMEBUFFER_BINDING */)
        savedViewport.clear()
        GL11.glGetInteger(GL11.GL_VIEWPORT, savedViewport)

        val prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        ensureTargets(w, h)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex)

        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, maskFbo)
        GL11.glViewport(0, 0, w, h)
        // 清空画布要临时把清屏颜色设成透明黑，清完马上改回去：
        // MC 每帧开头用这个颜色（天空 / 雾的颜色）清屏，它自己缓存着值，不改回去的话远处会露出一圈黑色
        savedClear.clear()
        GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, savedClear)
        GL11.glClearColor(0f, 0f, 0f, 0f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        GL11.glClearColor(savedClear.get(0), savedClear.get(1), savedClear.get(2), savedClear.get(3))
        active = true
        return true
    }

    /**
     * 把画布做成光晕，叠加到原来的帧缓冲上。
     * @param r g b    光晕颜色（乘在内容本身的颜色上）
     * @param exposure 亮度
     * @param spread   0..1，越大光晕越大越柔
     */
    override fun end(r: Float, g: Float, b: Float, exposure: Float, spread: Float) {
        if (!active) return
        active = false
        val caps = GlCaps.save()

        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glDisable(GL11.GL_LIGHTING)
        GL11.glDisable(GL11.GL_FOG)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glColor4f(1f, 1f, 1f, 1f)

        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()

        // 逐级缩小
        GL11.glDisable(GL11.GL_BLEND)
        GL20.glUseProgram(progDown)
        GL20.glUniform1i(GL20.glGetUniformLocation(progDown, "tex"), 0)
        val texelDown = GL20.glGetUniformLocation(progDown, "texel")
        var srcTex = maskTex
        var srcW = width
        var srcH = height
        for (i in 0 until LEVELS) {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, levelFbo[i])
            GL11.glViewport(0, 0, levelW[i], levelH[i])
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, srcTex)
            GL20.glUniform2f(texelDown, 1f / srcW, 1f / srcH)
            quad()
            srcTex = levelTex[i]
            srcW = levelW[i]
            srcH = levelH[i]
        }

        // 逐级放大并叠加（加法混合）
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE)
        GL20.glUseProgram(progUp)
        GL20.glUniform1i(GL20.glGetUniformLocation(progUp, "tex"), 0)
        val texelUp = GL20.glGetUniformLocation(progUp, "texel")
        GL20.glUniform1f(GL20.glGetUniformLocation(progUp, "intensity"), 0.55f + spread.coerceIn(0f, 1f) * 0.6f)
        for (i in LEVELS - 2 downTo 0) {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, levelFbo[i])
            GL11.glViewport(0, 0, levelW[i], levelH[i])
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, levelTex[i + 1])
            GL20.glUniform2f(texelUp, 1f / levelW[i + 1], 1f / levelH[i + 1])
            quad()
        }

        // 合成到原来的画面上（加法混合）
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, savedFbo)
        GL11.glViewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3))
        GL20.glUseProgram(progComp)
        GL20.glUniform1i(GL20.glGetUniformLocation(progComp, "tex"), 0)
        GL20.glUniform3f(GL20.glGetUniformLocation(progComp, "tint"), r, g, b)
        GL20.glUniform1f(GL20.glGetUniformLocation(progComp, "exposure"), exposure)
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, levelTex[0])
        quad()

        GL20.glUseProgram(0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPopMatrix()
        GlCaps.restore(caps)   // 原样恢复雾等开关，否则天边会露出黑底
    }

    /** 中途出错时保证切回原来的帧缓冲 */
    fun abort() {
        if (!active) return
        active = false
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, savedFbo)
        GL11.glViewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3))
    }

    private fun quad() {
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(-1f, -1f)
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(1f, -1f)
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(1f, 1f)
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(-1f, 1f)
        GL11.glEnd()
    }
}


