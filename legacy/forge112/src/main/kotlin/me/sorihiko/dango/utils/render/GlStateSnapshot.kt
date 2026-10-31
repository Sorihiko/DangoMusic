package me.sorihiko.dango.utils.render

import net.minecraft.client.renderer.GlStateManager
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20

/**
 * 记下当前的 OpenGL 状态，restore() 时同时通过 GlStateManager 和原生 GL 还原。
 *
 * 为什么要"两边都设"：GlStateManager（以及 Lunar / OptiFine 自己的版本）会缓存状态，
 * 缓存认为已经是某个值时就跳过真正的 GL 调用。如果我们用原生 GL 改了状态，
 * 缓存和真实状态就对不上，后面的渲染会出错（例如文字、粒子丢失纹理变成纯色方块）。
 * 两边都设一遍，就能保证缓存 == 真实 == 进来时的状态。
 */
class GlStateSnapshot {
    private var program = 0
    private var activeTex = GL13.GL_TEXTURE0
    private val tex2d = BooleanArray(2)
    private val binding = IntArray(2)
    private val lightmapCoord = FloatArray(4)
    private var blend = false
    private var depthTest = false
    private var cull = false
    private var alphaTest = false
    private var fog = false
    private var lighting = false
    private var depthMask = true
    private var srcRgb = GL11.GL_ONE
    private var dstRgb = GL11.GL_ZERO
    private var srcAlpha = GL11.GL_ONE
    private var dstAlpha = GL11.GL_ZERO
    private var shadeModel = GL11.GL_SMOOTH
    private var matrixMode = GL11.GL_MODELVIEW
    private val color = FloatArray(4)

    init {
        val buf = BufferUtils.createFloatBuffer(16)
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        activeTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        for (i in 0..1) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i)
            tex2d[i] = GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
            binding[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
            if (i == 1) {
                // 1 号单元的"当前纹理坐标"就是光照贴图坐标，原版还会缓存它
                buf.clear()
                GL11.glGetFloat(GL11.GL_CURRENT_TEXTURE_COORDS, buf)
                for (k in 0..3) lightmapCoord[k] = buf.get(k)
            }
        }
        GL13.glActiveTexture(activeTex)

        blend = GL11.glIsEnabled(GL11.GL_BLEND)
        depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
        cull = GL11.glIsEnabled(GL11.GL_CULL_FACE)
        alphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
        fog = GL11.glIsEnabled(GL11.GL_FOG)
        lighting = GL11.glIsEnabled(GL11.GL_LIGHTING)
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
        dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
        srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
        dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
        shadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL)
        matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE)

        buf.clear()
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, buf)
        for (k in 0..3) color[k] = buf.get(k)
    }

    private inline fun cap(on: Boolean, cap: Int, enable: () -> Unit, disable: () -> Unit) {
        if (on) {
            enable()
            GL11.glEnable(cap)
        } else {
            disable()
            GL11.glDisable(cap)
        }
    }

    fun restore() {
        GL20.glUseProgram(program)

        for (i in 1 downTo 0) {
            val unit = GL13.GL_TEXTURE0 + i
            GlStateManager.setActiveTexture(unit)
            GL13.glActiveTexture(unit)
            cap(tex2d[i], GL11.GL_TEXTURE_2D, { GlStateManager.enableTexture2D() }, { GlStateManager.disableTexture2D() })
            GlStateManager.bindTexture(binding[i])
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding[i])
            if (i == 1) {
                GL13.glMultiTexCoord4f(unit, lightmapCoord[0], lightmapCoord[1], lightmapCoord[2], lightmapCoord[3])
            }
        }
        GlStateManager.setActiveTexture(activeTex)
        GL13.glActiveTexture(activeTex)

        cap(blend, GL11.GL_BLEND, { GlStateManager.enableBlend() }, { GlStateManager.disableBlend() })
        cap(depthTest, GL11.GL_DEPTH_TEST, { GlStateManager.enableDepth() }, { GlStateManager.disableDepth() })
        cap(cull, GL11.GL_CULL_FACE, { GlStateManager.enableCull() }, { GlStateManager.disableCull() })
        cap(alphaTest, GL11.GL_ALPHA_TEST, { GlStateManager.enableAlpha() }, { GlStateManager.disableAlpha() })
        cap(fog, GL11.GL_FOG, { GlStateManager.enableFog() }, { GlStateManager.disableFog() })
        cap(lighting, GL11.GL_LIGHTING, { GlStateManager.enableLighting() }, { GlStateManager.disableLighting() })

        GlStateManager.depthMask(depthMask)
        GL11.glDepthMask(depthMask)
        GlStateManager.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha)
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha)
        GlStateManager.shadeModel(shadeModel)
        GL11.glShadeModel(shadeModel)
        GlStateManager.color(color[0], color[1], color[2], color[3])
        GL11.glColor4f(color[0], color[1], color[2], color[3])
        GL11.glMatrixMode(matrixMode)
    }
}


