package me.sorihiko.dango.lyrics

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.gfx.GlassTextPass
import me.sorihiko.dango.gfx.GlassTextStyle
import me.sorihiko.dango.gfx.GlassTextView
import me.sorihiko.dango.modern.Fixed
import me.sorihiko.dango.modern.Gl
import me.sorihiko.dango.modern.GlState
import me.sorihiko.dango.modern.Imm
import me.sorihiko.dango.modern.McCompat
import me.sorihiko.dango.modern.ModernGfx
import me.sorihiko.dango.ui.glass.GlassBackdrop

import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

import java.nio.ByteBuffer
import kotlin.math.max

/**
 * 玻璃字（驾驶舱歌词用），OpenGL 3.2 core 版本，做法和 1.8.9 版一样：
 *   1. 截一张当前画面（玻璃后面的世界）
 *   2. 把字画到一张离屏遮罩上：alpha = 玻璃在哪，rgb = 这块玻璃自己发多少光；画的时候带透视（倾斜）
 *   3. 遮罩逐级缩小模糊：小半径的几级拿来当"高度"，算出玻璃表面的法线（字中间厚、边上薄）；
 *      再逐级放大叠回来，得到发光的光晕（流程和 [DeepGlow] 一样）
 *   4. 合成：字的范围里沿法线折射取背后的画面（带一点色散），玻璃吸收一点光、里面透出字的颜色，
 *      边缘按菲涅耳和左上角的光打出亮边；字外面叠光晕。实心字就是不折射、直接画成发光的字
 *
 * 透视：把投影换成一个视锥（[McCompat.pushScreenProjection]），z = 0 的平面正好铺满窗口，
 * 字的模型矩阵（[Fixed]）绕支点倾斜，所以 [me.sorihiko.dango.gfx.TextCanvas] 画的字自然带透视。
 *
 * 着色器编译失败等情况下，字直接画到屏幕上（透视照样有）。只能在渲染线程调用。
 */
class GlassText : GlassTextPass {

    private companion object {
        const val LEVELS = 6
        /** 镜头到屏幕的距离 = 窗口高度 × 这个数；越大透视越弱（1.6 ≈ 35° 视角） */
        const val FOCAL = 1.6f
        const val GL_RGBA16F = 0x881A

        const val VERT = """
#version 150
in vec2 a_Pos;
in vec2 a_UV;
out vec2 v_UV;
void main() {
    v_UV = a_UV;
    gl_Position = vec4(a_Pos, 0.0, 1.0);
}
"""

        const val DOWN = """
#version 150
in vec2 v_UV;
out vec4 fragColor;
uniform sampler2D tex;
uniform vec2 texel;
void main() {
    vec2 uv = v_UV;
    vec4 c = texture(tex, uv) * 4.0;
    c += texture(tex, uv - texel);
    c += texture(tex, uv + texel);
    c += texture(tex, uv + vec2(texel.x, -texel.y));
    c += texture(tex, uv + vec2(-texel.x, texel.y));
    fragColor = c / 8.0;
}
"""

        const val COPY = """
#version 150
in vec2 v_UV;
out vec4 fragColor;
uniform sampler2D tex;
void main() {
    fragColor = texture(tex, v_UV);
}
"""

        const val UP = """
#version 150
in vec2 v_UV;
out vec4 fragColor;
uniform sampler2D tex;
uniform vec2 texel;
uniform float intensity;
void main() {
    vec2 uv = v_UV;
    vec4 s = texture(tex, uv + vec2(-2.0 * texel.x, 0.0));
    s += texture(tex, uv + vec2(2.0 * texel.x, 0.0));
    s += texture(tex, uv + vec2(0.0, -2.0 * texel.y));
    s += texture(tex, uv + vec2(0.0, 2.0 * texel.y));
    s += texture(tex, uv + vec2(-texel.x, texel.y)) * 2.0;
    s += texture(tex, uv + vec2(texel.x, texel.y)) * 2.0;
    s += texture(tex, uv + vec2(texel.x, -texel.y)) * 2.0;
    s += texture(tex, uv + vec2(-texel.x, -texel.y)) * 2.0;
    fragColor = s / 12.0 * intensity;
}
"""

        // 合成：mask = 遮罩（预乘：rgb = 字的颜色 × 亮度，颜色最亮的通道是 1），d0 / d1 = 模糊后的遮罩（算法线），
        // halo = 放大叠回来的光晕（带每个字自己的颜色），sharp / soft = 字后面的画面（清晰 / 轻度模糊）
        const val COMPOSITE = """
#version 150
out vec4 fragColor;
uniform sampler2D mask;
uniform sampler2D d0;
uniform sampler2D d1;
uniform sampler2D halo;
uniform sampler2D sharp;
uniform sampler2D soft;
uniform vec2 screen;
uniform vec2 t0;
uniform vec2 t1;
uniform float opacity;
uniform float refraction;
uniform float emission;
uniform float haloAmt;
uniform float frost;
uniform float solid;
uniform float tintText;
uniform float brightness;
uniform float premul;
float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }
void main() {
    vec2 uv = gl_FragCoord.xy / screen;
    vec4 m = texture(mask, uv);
    float cov = m.a;
    vec3 energy = texture(halo, uv).rgb * haloAmt;
    if (cov < 0.003 && energy.r + energy.g + energy.b < 0.003) discard;
    vec3 g = vec3(1.0) - exp(-energy);
    g = floor(g * 255.0 + rand(gl_FragCoord.xy)) / 255.0;
    float gMax = max(g.r, max(g.g, g.b));
    if (cov < 0.003) {
        // 画在透明的离屏层上（预乘）时，光晕的透明度跟着亮度走，和 DeepGlow 一样；画在实心画面上时不动目标的透明度
        fragColor = vec4(g, premul > 0.5 ? gMax : 0.0);
        return;
    }

    float hx = (texture(d0, uv + vec2(t0.x, 0.0)).a - texture(d0, uv - vec2(t0.x, 0.0)).a) * 0.6
             + (texture(d1, uv + vec2(t1.x, 0.0)).a - texture(d1, uv - vec2(t1.x, 0.0)).a) * 0.4;
    float hy = (texture(d0, uv + vec2(0.0, t0.y)).a - texture(d0, uv - vec2(0.0, t0.y)).a) * 0.6
             + (texture(d1, uv + vec2(0.0, t1.y)).a - texture(d1, uv - vec2(0.0, t1.y)).a) * 0.4;
    vec3 n = normalize(vec3(-hx * 2.4, -hy * 2.4, 1.0));
    vec3 c = clamp(m.rgb / max(cov, 0.02), 0.0, 1.0);
    float lit = max(c.r, max(c.g, c.b));
    vec3 hue = c / max(lit, 0.001);

    vec2 off = -n.xy * refraction / screen;
    vec3 sharpC = vec3(texture(sharp, uv + off * 1.25).r, texture(sharp, uv + off).g, texture(sharp, uv + off * 0.75).b);
    vec3 bg = mix(sharpC, texture(soft, uv + off).rgb, frost);
    float fres = pow(1.0 - n.z, 1.3);
    float spec = pow(max(dot(n, normalize(vec3(-0.5, 0.55, 0.68))), 0.0), 22.0);
    vec3 rim = mix(vec3(1.0), hue, 0.35) * (fres * 1.1 + spec * 0.7) * (0.45 + 0.55 * lit);
    vec3 inner = hue * emission * (0.2 + 0.8 * lit);
    vec3 lightC = (inner + rim) * brightness;
    vec3 glassC = bg * 0.78 + max(lightC, vec3(0.0));
    vec3 solidC = mix(vec3(1.0), hue, tintText) * (0.35 + 0.65 * lit) * brightness + inner * 0.4;
    vec3 body = mix(glassC, solidC, solid);
    float a = cov * opacity;
    fragColor = vec4(body * a + g * (1.0 - a), premul > 0.5 ? a + (1.0 - a) * gMax : a);
}
"""
    }

    private var progDown = 0
    private var progCopy = 0
    private var progUp = 0
    private var progComp = 0
    private var failed = false

    private val backdrop by lazy { GlassBackdrop() }

    // 遮罩 8 位；模糊金字塔用半精度浮点：叠加不会在 1.0 截断，淡光晕和法线没有色带
    private var width = 0
    private var height = 0
    private var maskTex = 0
    private var maskFbo = 0
    private val downTex = IntArray(LEVELS)
    private val downFbo = IntArray(LEVELS)
    private val upTex = IntArray(LEVELS - 1)
    private val upFbo = IntArray(LEVELS - 1)
    private val levelW = IntArray(LEVELS)
    private val levelH = IntArray(LEVELS)

    /** 着色器 uniform 的位置（链接后不变，缓存起来不用每帧查） */
    private val uniforms = HashMap<String, Int>()

    private var savedFbo = 0
    private var savedReadFbo = 0
    private val savedViewport = BufferUtils.createIntBuffer(16)
    private val savedClear = BufferUtils.createFloatBuffer(16)
    private var state: GlState? = null
    private val savedPose = Matrix4f()
    private val savedColor = FloatArray(4)
    private var projected = false
    /** 这一帧画在透明的离屏层上（1.21.10+ / 26.x 的 HUD 层），合成时透明度要一起写 */
    private var premultiplied = false

    /** 0 = 没在画，1 = 画进遮罩（玻璃），2 = 直接画到屏幕（做不了玻璃时） */
    private var mode = 0

    // ---------------------------------------------------------------- 初始化

    private fun compile(type: Int, src: String): Int {
        val id = GL20.glCreateShader(type)
        GL20.glShaderSource(id, src)
        GL20.glCompileShader(id)
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetShaderInfoLog(id, 4096)
            GL20.glDeleteShader(id)
            error("Glass text shader compile failed: $log")
        }
        return id
    }

    private fun program(frag: String): Int {
        val vs = compile(GL20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GL20.GL_FRAGMENT_SHADER, frag)
        val p = GL20.glCreateProgram()
        GL20.glAttachShader(p, vs)
        GL20.glAttachShader(p, fs)
        GL20.glBindAttribLocation(p, Imm.ATTR_POS, "a_Pos")
        GL20.glBindAttribLocation(p, Imm.ATTR_UV, "a_UV")
        GL20.glLinkProgram(p)
        GL20.glDeleteShader(vs)
        GL20.glDeleteShader(fs)
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            error("Glass text shader link failed: " + GL20.glGetProgramInfoLog(p, 4096))
        }
        return p
    }

    private fun usable(): Boolean {
        if (failed) return false
        if (progComp != 0) return true
        return try {
            progDown = program(DOWN)
            progCopy = program(COPY)
            progUp = program(UP)
            progComp = program(COMPOSITE)
            true
        } catch (e: Throwable) {
            DangoLogger.warn("Glass text disabled: {}", e.toString())
            failed = true
            false
        }
    }

    private fun disable(reason: Throwable) {
        if (!failed) DangoLogger.warn("Glass text disabled after error: {}", reason.toString())
        failed = true
    }

    private fun newTexture(w: Int, h: Int, hdr: Boolean): Int {
        val id = Gl.genTexture()
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        Gl.withDefaultUnpack {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, if (hdr) GL_RGBA16F else GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
        }
        return id
    }

    private fun newFbo(tex: Int): Int {
        val prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        val prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        val fbo = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0)
        restoreFramebuffers(prevDraw, prevRead)
        return fbo
    }

    /** 读、写分开恢复：光影可能读一个、写另一个（和 DeepGlow 一样） */
    private fun restoreFramebuffers(draw: Int, read: Int) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw)
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read)
    }

    private fun release() {
        if (maskTex != 0) Gl.deleteTexture(maskTex)
        if (maskFbo != 0) GL30.glDeleteFramebuffers(maskFbo)
        maskTex = 0
        maskFbo = 0
        for (i in 0 until LEVELS) {
            if (downTex[i] != 0) Gl.deleteTexture(downTex[i])
            if (downFbo[i] != 0) GL30.glDeleteFramebuffers(downFbo[i])
            downTex[i] = 0
            downFbo[i] = 0
        }
        for (i in 0 until LEVELS - 1) {
            if (upTex[i] != 0) Gl.deleteTexture(upTex[i])
            if (upFbo[i] != 0) GL30.glDeleteFramebuffers(upFbo[i])
            upTex[i] = 0
            upFbo[i] = 0
        }
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
            downTex[i] = newTexture(lw, lh, true)
            downFbo[i] = newFbo(downTex[i])
            if (i < LEVELS - 1) {
                upTex[i] = newTexture(lw, lh, true)
                upFbo[i] = newFbo(upTex[i])
            }
        }
    }

    // ---------------------------------------------------------------- 画字

    override fun begin(view: GlassTextView): Boolean {
        if (mode != 0) return false
        val window = Minecraft.getInstance().window
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return false
        Imm.flush()
        Gl.forget()
        state = GlState()
        savedPose.set(Fixed.pose)
        Fixed.color.copyInto(savedColor)
        savedFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        savedReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        savedViewport.clear()
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport)
        premultiplied = Gl.premultipliedTarget
        mode = 2
        if (usable()) {
            try {
                val prevTex = Gl.currentTexture()
                ensureTargets(w, h)
                Gl.bindTexture(prevTex)
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, maskFbo)
                GL11.glViewport(0, 0, w, h)
                Gl.scissor(false)
                Gl.colorMask(true, true, true, true)
                // 清屏颜色临时设成透明黑，清完改回去（MC 自己缓存着天空颜色）
                savedClear.clear()
                GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, savedClear)
                GL11.glClearColor(0f, 0f, 0f, 0f)
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
                GL11.glClearColor(savedClear.get(0), savedClear.get(1), savedClear.get(2), savedClear.get(3))
                mode = 1
            } catch (t: Throwable) {
                disable(t)
                restoreFramebuffers(savedFbo, savedReadFbo)
                restoreViewport()
            }
        }

        // 透视：z = 0 的平面正好铺满窗口（1 单位 = 1 像素，y 向下），再绕支点倾斜
        val d = h * FOCAL
        val near = d * 0.25f
        val sx = w / 2f * near / d
        val sy = h / 2f * near / d
        McCompat.pushScreenProjection(Matrix4f().frustum(-sx, sx, -sy, sy, near, d * 4f))
        McCompat.pushIdentityModelView()
        projected = true
        Fixed.reset(
            Matrix4f()
                .translation(0f, 0f, -d)
                .scale(1f, -1f, 1f)
                .translate(-w / 2f, -h / 2f, 0f)
                .translate(view.pivotX, view.pivotY, 0f)
                .rotateY(Math.toRadians(view.yaw.toDouble()).toFloat())
                .rotateX(Math.toRadians(view.pitch.toDouble()).toFloat())
                .translate(-view.pivotX, -view.pivotY, 0f)
        )
        ModernGfx.textState()
        return true
    }

    override fun bar(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, alphaTop: Float, alphaBottom: Float) {
        if (mode == 0 || w <= 0f || h <= 0f) return
        // 没有逐顶点颜色：分成几段，每段一个透明度，看起来就是渐变
        val steps = 16
        val cr = r.coerceIn(0f, 1f)
        val cg = g.coerceIn(0f, 1f)
        val cb = b.coerceIn(0f, 1f)
        for (i in 0 until steps) {
            val t0 = i / steps.toFloat()
            val t1 = (i + 1) / steps.toFloat()
            val a = (alphaTop + (alphaBottom - alphaTop) * (t0 + t1) / 2f).coerceIn(0f, 1f)
            if (a <= 0.002f) continue
            Fixed.color(cr, cg, cb, a)
            Imm.begin(textured = false)
            Imm.vertex(x, y + h * t0)
            Imm.vertex(x + w, y + h * t0)
            Imm.vertex(x + w, y + h * t1)
            Imm.vertex(x, y + h * t1)
            Imm.endFixed()
        }
    }

    override fun end(style: GlassTextStyle) {
        val m = mode
        if (m == 0) return
        mode = 0
        try {
            try {
                Imm.flush()
            } finally {
                // 出错也要把视锥投影换回去，不然之后画的东西全歪
                if (projected) {
                    projected = false
                    McCompat.popModelView()
                    McCompat.popScreenProjection()
                }
            }
            Fixed.reset(savedPose)
            Fixed.color(savedColor[0], savedColor[1], savedColor[2], savedColor[3])
            if (m == 1) composite(style)
        } catch (t: Throwable) {
            disable(t)
        } finally {
            Imm.flush()
            if (m == 1) restoreFramebuffers(savedFbo, savedReadFbo)
            restoreViewport()
            state?.restore()
            state = null
            Gl.forget()
        }
    }

    private fun restoreViewport() {
        GL11.glViewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3))
    }

    // ---------------------------------------------------------------- 合成

    private val savedUnits = IntArray(6)

    private fun composite(s: GlassTextStyle) {
        // 玻璃才要后面的画面；实心字（默认）不用截。
        // 26.x 选 Vulkan 时 HUD 画在单独的离屏层上，截到的只有 HUD 没有世界，这时也画成实心字
        val solid = s.solid || McCompat.offscreenGl
        if (!solid) {
            restoreFramebuffers(savedFbo, savedReadFbo)
            restoreViewport()
            backdrop.capture()
        }
        Gl.forget()
        Gl.depthTest(false)
        Gl.cull(false)
        Gl.scissor(false)
        for (i in 1 until savedUnits.size) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i)
            savedUnits[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        Gl.forget()
        Gl.activeTexture(GL13.GL_TEXTURE0)
        try {
            // 逐级缩小（每级顺便模糊一次）
            Gl.blend(false)
            GL20.glUseProgram(progDown)
            GL20.glUniform1i(loc(progDown, "tex"), 0)
            var srcTex = maskTex
            var srcW = width
            var srcH = height
            for (i in 0 until LEVELS) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, downFbo[i])
                GL11.glViewport(0, 0, levelW[i], levelH[i])
                Gl.bindTexture(srcTex)
                GL20.glUniform2f(loc(progDown, "texel"), 1f / srcW, 1f / srcH)
                quad()
                srcTex = downTex[i]
                srcW = levelW[i]
                srcH = levelH[i]
            }

            // 逐级放大叠回来（放进另一组贴图，缩小的那几级留着算法线）
            for (i in LEVELS - 2 downTo 0) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, upFbo[i])
                GL11.glViewport(0, 0, levelW[i], levelH[i])
                Gl.blend(false)
                GL20.glUseProgram(progCopy)
                GL20.glUniform1i(loc(progCopy, "tex"), 0)
                Gl.bindTexture(downTex[i])
                quad()

                val fromTex = if (i == LEVELS - 2) downTex[LEVELS - 1] else upTex[i + 1]
                Gl.blend(true)
                Gl.blendFunc(GL11.GL_ONE, GL11.GL_ONE)
                GL20.glUseProgram(progUp)
                GL20.glUniform1i(loc(progUp, "tex"), 0)
                GL20.glUniform2f(loc(progUp, "texel"), 1f / levelW[i + 1], 1f / levelH[i + 1])
                GL20.glUniform1f(loc(progUp, "intensity"), 0.8f)
                Gl.bindTexture(fromTex)
                quad()
            }

            // 合成到原来的画面上
            restoreFramebuffers(savedFbo, savedReadFbo)
            restoreViewport()
            val p = progComp
            GL20.glUseProgram(p)
            fun u(name: String) = loc(p, name)
            GL20.glUniform1i(u("mask"), 0)
            GL20.glUniform1i(u("d0"), 1)
            GL20.glUniform1i(u("d1"), 2)
            GL20.glUniform1i(u("halo"), 3)
            GL20.glUniform1i(u("sharp"), 4)
            GL20.glUniform1i(u("soft"), 5)
            GL20.glUniform2f(u("screen"), width.toFloat(), height.toFloat())
            GL20.glUniform2f(u("t0"), 1f / levelW[0], 1f / levelH[0])
            GL20.glUniform2f(u("t1"), 1f / levelW[1], 1f / levelH[1])
            GL20.glUniform1f(u("tintText"), s.tintText.coerceIn(0f, 1f))
            GL20.glUniform1f(u("brightness"), s.brightness.coerceIn(0f, 2f))
            GL20.glUniform1f(u("opacity"), s.opacity.coerceIn(0f, 1f))
            GL20.glUniform1f(u("refraction"), s.refraction.coerceAtLeast(0f))
            GL20.glUniform1f(u("emission"), s.emission.coerceAtLeast(0f))
            GL20.glUniform1f(u("haloAmt"), s.halo.coerceAtLeast(0f))
            GL20.glUniform1f(u("frost"), s.frost.coerceIn(0f, 1f))
            GL20.glUniform1f(u("solid"), if (solid) 1f else 0f)
            GL20.glUniform1f(u("premul"), if (premultiplied) 1f else 0f)
            bindUnit(1, downTex[0])
            bindUnit(2, downTex[1])
            bindUnit(3, upTex[0])
            // 实心字不采样后面的画面，随便绑一张有效的贴图就行（遮罩）
            bindUnit(4, if (solid) maskTex else backdrop.sharpTexture)
            bindUnit(5, if (solid) maskTex else backdrop.softTexture)
            bindUnit(0, maskTex)
            Gl.forget()
            Gl.blend(true)
            // 颜色是预乘的。画在实心画面上时目标的 alpha 不动；画在透明的离屏层上时 alpha 也要叠加，
            // 不然这一层贴到屏幕上时字会被当成透明的、叠加成两倍亮
            if (premultiplied) Gl.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
            else Gl.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE)
            quad()
        } finally {
            Imm.flush()
            for (i in 1 until savedUnits.size) bindUnit(i, savedUnits[i])
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            Gl.forget()
        }
    }

    private fun loc(program: Int, name: String): Int =
        uniforms.getOrPut("$program:$name") { GL20.glGetUniformLocation(program, name) }

    private fun bindUnit(unit: Int, tex: Int) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
    }

    private fun quad() {
        Imm.begin()
        Imm.tex(0f, 0f); Imm.vertex(-1f, -1f)
        Imm.tex(1f, 0f); Imm.vertex(1f, -1f)
        Imm.tex(1f, 1f); Imm.vertex(1f, 1f)
        Imm.tex(0f, 1f); Imm.vertex(-1f, 1f)
        Imm.end()
    }
}
