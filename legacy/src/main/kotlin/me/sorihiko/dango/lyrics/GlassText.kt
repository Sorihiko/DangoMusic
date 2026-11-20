package me.sorihiko.dango.lyrics

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.gfx.GlassTextPass
import me.sorihiko.dango.gfx.GlassTextStyle
import me.sorihiko.dango.gfx.GlassTextView
import me.sorihiko.dango.legacy.LegacyGfx
import me.sorihiko.dango.legacy.mc
import me.sorihiko.dango.ui.glass.GlassBackdrop
import me.sorihiko.dango.ui.glass.GlassTarget
import me.sorihiko.dango.utils.render.GlCaps

import net.minecraft.client.renderer.OpenGlHelper
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GLContext

import kotlin.math.max

/**
 * 玻璃字（座舱 HUD 风格的侧边歌词用）：
 *   1. 截一张当前画面（玻璃后面的世界）
 *   2. 把字画到一张离屏遮罩上：alpha = 玻璃在哪，rgb = 这块玻璃自己发多少光；画的时候带透视（倾斜）
 *   3. 遮罩逐级缩小模糊：小半径的几级拿来当“高度”，算出玻璃表面的法线（字中间厚、边上薄）；
 *      再逐级放大叠回来，得到发光的光晕
 *   4. 合成：字的范围里沿法线折射取背后的画面（带一点色散），玻璃吸收一点光、里面透出封面色的光，
 *      边缘按菲涅耳和左上角的光打出亮边；字外面叠光晕
 *
 * 显卡做不了（没有 GL 3.0 / 着色器编译失败）时，字直接画到屏幕上，透视照样有。
 * 只能在渲染线程调用。
 */
class GlassText : GlassTextPass {

    private companion object {
        const val LEVELS = 6
        /** 镜头到屏幕的距离 = 窗口高度 × 这个数；越大透视越弱（1.6 ≈ 35° 视角） */
        const val FOCAL = 1.6f

        const val VERT = """
#version 120
void main() {
    gl_TexCoord[0] = gl_MultiTexCoord0;
    gl_Position = gl_Vertex;
}
"""

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

        const val COPY = """
#version 120
uniform sampler2D tex;
void main() {
    gl_FragColor = texture2D(tex, gl_TexCoord[0].xy);
}
"""

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

        // 合成：mask = 遮罩（预乘：rgb = 字的颜色 × 亮度，颜色最亮的通道是 1），d0 / d1 = 模糊后的遮罩（算法线），
        // halo = 放大叠回来的光晕（带每个字自己的颜色），sharp / soft = 字后面的画面（清晰 / 轻度模糊）
        const val COMPOSITE = """
#version 120
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
float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }
void main() {
    vec2 uv = gl_FragCoord.xy / screen;
    vec4 m = texture2D(mask, uv);
    float cov = m.a;
    vec3 energy = texture2D(halo, uv).rgb * haloAmt;
    if (cov < 0.003 && energy.r + energy.g + energy.b < 0.003) discard;
    vec3 g = vec3(1.0) - exp(-energy);
    g = floor(g * 255.0 + rand(gl_FragCoord.xy)) / 255.0;
    if (cov < 0.003) {
        gl_FragColor = vec4(g, 0.0);
        return;
    }

    float hx = (texture2D(d0, uv + vec2(t0.x, 0.0)).a - texture2D(d0, uv - vec2(t0.x, 0.0)).a) * 0.6
             + (texture2D(d1, uv + vec2(t1.x, 0.0)).a - texture2D(d1, uv - vec2(t1.x, 0.0)).a) * 0.4;
    float hy = (texture2D(d0, uv + vec2(0.0, t0.y)).a - texture2D(d0, uv - vec2(0.0, t0.y)).a) * 0.6
             + (texture2D(d1, uv + vec2(0.0, t1.y)).a - texture2D(d1, uv - vec2(0.0, t1.y)).a) * 0.4;
    vec3 n = normalize(vec3(-hx * 2.4, -hy * 2.4, 1.0));
    vec3 c = clamp(m.rgb / max(cov, 0.02), 0.0, 1.0);
    float lit = max(c.r, max(c.g, c.b));
    vec3 hue = c / max(lit, 0.001);

    vec2 off = -n.xy * refraction / screen;
    vec3 sharpC = vec3(texture2D(sharp, uv + off * 1.25).r, texture2D(sharp, uv + off).g, texture2D(sharp, uv + off * 0.75).b);
    vec3 bg = mix(sharpC, texture2D(soft, uv + off).rgb, frost);
    float fres = pow(1.0 - n.z, 1.3);
    float spec = pow(max(dot(n, normalize(vec3(-0.5, 0.55, 0.68))), 0.0), 22.0);
    vec3 rim = mix(vec3(1.0), hue, 0.35) * (fres * 1.1 + spec * 0.7) * (0.45 + 0.55 * lit);
    vec3 inner = hue * emission * (0.2 + 0.8 * lit);
    vec3 lightC = (inner + rim) * brightness;
    vec3 glassC = bg * 0.78 + max(lightC, vec3(0.0));
    vec3 solidC = mix(vec3(1.0), hue, tintText) * (0.35 + 0.65 * lit) * brightness + inner * 0.4;
    vec3 body = mix(glassC, solidC, solid);
    float a = cov * opacity;
    gl_FragColor = vec4(body * a + g * (1.0 - a), a);
}
"""
    }

    private var progDown = 0
    private var progCopy = 0
    private var progUp = 0
    private var progComp = 0
    private var failed = false

    private val backdrop by lazy { GlassBackdrop() }
    private val mask = GlassTarget()
    // 半精度浮点：叠加不会在 1.0 截断，淡光晕和法线没有色带（GL 3.0 本来就要求支持）
    private val down = Array(LEVELS) { GlassTarget(GL30.GL_RGBA16F) }
    private val up = Array(LEVELS - 1) { GlassTarget(GL30.GL_RGBA16F) }
    /** 着色器 uniform 的位置（链接后不变，缓存起来不用每帧查） */
    private val uniforms = HashMap<String, Int>()

    private var savedFbo = 0
    private val savedViewport = BufferUtils.createIntBuffer(16)
    private val savedClear = BufferUtils.createFloatBuffer(16)
    private var caps: GlCaps.Saved? = null

    /** 0 = 没在画，1 = 画进遮罩（玻璃），2 = 直接画到屏幕（做不了玻璃时） */
    private var mode = 0

    // ---------------------------------------------------------------- 初始化

    private fun compile(type: Int, src: String): Int {
        val id = GL20.glCreateShader(type)
        // 和 DeepGlow 一样走 UTF-8 字节缓冲（LWJGL 2 的字符串重载会弄坏非 ASCII 字符）
        val bytes = src.toByteArray(Charsets.UTF_8)
        val source = BufferUtils.createByteBuffer(bytes.size)
        source.put(bytes).flip()
        GL20.glShaderSource(id, source)
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
        GL20.glLinkProgram(p)
        GL20.glDeleteShader(vs)
        GL20.glDeleteShader(fs)
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            error("Glass text shader link failed: " + GL20.glGetProgramInfoLog(p, 4096))
        }
        return p
    }

    /** 能不能做玻璃：要 GL 3.0 的帧缓冲（液态玻璃本身也要），着色器要编译得过 */
    private fun usable(): Boolean {
        if (failed) return false
        if (!GLContext.getCapabilities().OpenGL30) {
            failed = true
            DangoLogger.warn("Glass text: OpenGL 3.0 not available, side lyrics fall back to plain text")
            return false
        }
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

    private fun ensureTargets(w: Int, h: Int) {
        mask.ensure(w, h)
        var lw = w
        var lh = h
        for (i in 0 until LEVELS) {
            lw = max(1, lw / 2)
            lh = max(1, lh / 2)
            down[i].ensure(lw, lh)
            if (i < LEVELS - 1) up[i].ensure(lw, lh)
        }
    }

    // ---------------------------------------------------------------- 画字

    override fun begin(view: GlassTextView): Boolean {
        if (mode != 0) return false
        val w = mc.displayWidth
        val h = mc.displayHeight
        if (w <= 0 || h <= 0) return false

        caps = GlCaps.save()
        savedViewport.clear()
        GL11.glGetInteger(GL11.GL_VIEWPORT, savedViewport)
        GL20.glUseProgram(0)
        mode = 2
        if (usable()) {
            savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
            try {
                backdrop.capture(frost = false)
                val prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
                ensureTargets(w, h)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex)
                mask.bind()
                // 裁剪开着的话 glClear 只清一块，遮罩上会留着上一帧的字（end 里会恢复裁剪状态）
                GL11.glDisable(GL11.GL_SCISSOR_TEST)
                // 清屏颜色临时设成透明黑，清完改回去（MC 自己缓存着天空颜色）
                savedClear.clear()
                GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, savedClear)
                GL11.glColorMask(true, true, true, true)
                GL11.glClearColor(0f, 0f, 0f, 0f)
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
                GL11.glClearColor(savedClear.get(0), savedClear.get(1), savedClear.get(2), savedClear.get(3))
                mode = 1
            } catch (t: Throwable) {
                disable(t)
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo)
                restoreViewport()
            }
        }

        // 透视：z = 0 的平面正好铺满窗口（1 单位 = 1 像素，y 向下），再绕支点倾斜
        val d = h * FOCAL
        val near = d * 0.25f
        val sx = w / 2.0 * near / d
        val sy = h / 2.0 * near / d
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glFrustum(-sx, sx, -sy, sy, near.toDouble(), d * 4.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glTranslatef(0f, 0f, -d)
        GL11.glScalef(1f, -1f, 1f)
        GL11.glTranslatef(-w / 2f, -h / 2f, 0f)
        GL11.glTranslatef(view.pivotX, view.pivotY, 0f)
        GL11.glRotatef(view.yaw, 0f, 1f, 0f)
        GL11.glRotatef(view.pitch, 1f, 0f, 0f)
        GL11.glTranslatef(-view.pivotX, -view.pivotY, 0f)

        // 平滑字体走固定管线：直接设 GL 状态，end 里原样恢复
        GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL13.glActiveTexture(OpenGlHelper.defaultTexUnit)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE)
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_LIGHTING)
        GL11.glDisable(GL11.GL_FOG)
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glShadeModel(GL11.GL_SMOOTH)
        GL11.glEnable(GL11.GL_BLEND)
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL14.glBlendEquation(GL14.GL_FUNC_ADD)
        return true
    }

    override fun bar(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, alphaTop: Float, alphaBottom: Float) {
        if (mode == 0 || w <= 0f || h <= 0f) return
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glColor4f(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alphaTop.coerceIn(0f, 1f))
        GL11.glVertex2f(x, y)
        GL11.glVertex2f(x + w, y)
        GL11.glColor4f(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alphaBottom.coerceIn(0f, 1f))
        GL11.glVertex2f(x + w, y + h)
        GL11.glVertex2f(x, y + h)
        GL11.glEnd()
        GL11.glEnable(GL11.GL_TEXTURE_2D)
    }

    override fun end(style: GlassTextStyle) {
        val m = mode
        if (m == 0) return
        mode = 0
        try {
            GL11.glMatrixMode(GL11.GL_PROJECTION)
            GL11.glPopMatrix()
            GL11.glMatrixMode(GL11.GL_MODELVIEW)
            GL11.glPopMatrix()
            if (m == 1) composite(style)
        } catch (t: Throwable) {
            disable(t)
        } finally {
            GL20.glUseProgram(0)
            if (m == 1) GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo)
            restoreViewport()
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
            caps?.let { GlCaps.restore(it) }
            caps = null
            LegacyGfx.resetColorAndTexture()
        }
    }

    private fun restoreViewport() {
        GL11.glViewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3))
    }

    // ---------------------------------------------------------------- 合成

    private val savedUnits = IntArray(6)

    private fun composite(s: GlassTextStyle) {
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        for (i in 1 until savedUnits.size) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i)
            savedUnits[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0)

        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        try {
            // 逐级缩小（每级顺便模糊一次）
            GL11.glDisable(GL11.GL_BLEND)
            GL20.glUseProgram(progDown)
            GL20.glUniform1i(loc(progDown, "tex"), 0)
            val texelDown = loc(progDown, "texel")
            var src = mask
            for (i in 0 until LEVELS) {
                down[i].bind()
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, src.texture)
                GL20.glUniform2f(texelDown, 1f / src.width, 1f / src.height)
                quad()
                src = down[i]
            }

            // 逐级放大叠回来（放进另一组贴图，缩小的那几级留着算法线）
            for (i in LEVELS - 2 downTo 0) {
                up[i].bind()
                GL11.glDisable(GL11.GL_BLEND)
                GL20.glUseProgram(progCopy)
                GL20.glUniform1i(loc(progCopy, "tex"), 0)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, down[i].texture)
                quad()

                val from = if (i == LEVELS - 2) down[LEVELS - 1] else up[i + 1]
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE)
                GL20.glUseProgram(progUp)
                GL20.glUniform1i(loc(progUp, "tex"), 0)
                GL20.glUniform2f(loc(progUp, "texel"), 1f / from.width, 1f / from.height)
                GL20.glUniform1f(loc(progUp, "intensity"), 0.8f)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, from.texture)
                quad()
            }

            // 合成到原来的画面上
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo)
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
            GL20.glUniform2f(u("screen"), mask.width.toFloat(), mask.height.toFloat())
            GL20.glUniform2f(u("t0"), 1f / down[0].width, 1f / down[0].height)
            GL20.glUniform2f(u("t1"), 1f / down[1].width, 1f / down[1].height)
            GL20.glUniform1f(u("tintText"), s.tintText.coerceIn(0f, 1f))
            GL20.glUniform1f(u("brightness"), s.brightness.coerceIn(0f, 2f))
            GL20.glUniform1f(u("opacity"), s.opacity.coerceIn(0f, 1f))
            GL20.glUniform1f(u("refraction"), s.refraction.coerceAtLeast(0f))
            GL20.glUniform1f(u("emission"), s.emission.coerceAtLeast(0f))
            GL20.glUniform1f(u("haloAmt"), s.halo.coerceAtLeast(0f))
            GL20.glUniform1f(u("frost"), s.frost.coerceIn(0f, 1f))
            GL20.glUniform1f(u("solid"), if (s.solid) 1f else 0f)
            bindUnit(1, down[0].texture)
            bindUnit(2, down[1].texture)
            bindUnit(3, up[0].texture)
            bindUnit(4, backdrop.sharpTexture)
            bindUnit(5, backdrop.softTexture)
            bindUnit(0, mask.texture)
            GL11.glEnable(GL11.GL_BLEND)
            // 颜色是预乘的；目标的 alpha 不动
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE)
            quad()
        } finally {
            for (i in 1 until savedUnits.size) bindUnit(i, savedUnits[i])
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glMatrixMode(GL11.GL_PROJECTION)
            GL11.glPopMatrix()
            GL11.glMatrixMode(GL11.GL_MODELVIEW)
            GL11.glPopMatrix()
        }
    }

    private fun loc(program: Int, name: String): Int =
        uniforms.getOrPut("$program:$name") { GL20.glGetUniformLocation(program, name) }

    private fun bindUnit(unit: Int, tex: Int) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
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
