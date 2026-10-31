package me.sorihiko.dango.utils.render

import net.minecraft.client.renderer.OpenGlHelper
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13

/**
 * 保存 / 恢复几个常用的 GL 开关（雾、光照、透明测试、剔除、深度、裁剪、混合、两层贴图）。
 *
 * 直接用 GL 改这些开关时，MC 的 GlStateManager 缓存不知道，事后它以为状态没变就不会再去设，
 * 比如雾被关掉后天空下半截的黑底会露出来。所以直接改 GL 状态的地方，画完都用这个原样恢复。
 */
object GlCaps {

    private val caps = intArrayOf(
        GL11.GL_FOG, GL11.GL_LIGHTING, GL11.GL_ALPHA_TEST, GL11.GL_CULL_FACE,
        GL11.GL_DEPTH_TEST, GL11.GL_SCISSOR_TEST, GL11.GL_BLEND,
    )

    class Saved internal constructor(
        val flags: BooleanArray,
        val tex0: Boolean,
        val tex1: Boolean,
        val activeTex: Int,
    )

    fun save(): Saved {
        val flags = BooleanArray(caps.size) { GL11.glIsEnabled(caps[it]) }
        val active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GL13.glActiveTexture(OpenGlHelper.defaultTexUnit)
        val t0 = GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
        GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit)
        val t1 = GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
        GL13.glActiveTexture(active)
        return Saved(flags, t0, t1, active)
    }

    fun restore(s: Saved) {
        for (i in caps.indices) set(caps[i], s.flags[i])
        GL13.glActiveTexture(OpenGlHelper.defaultTexUnit)
        set(GL11.GL_TEXTURE_2D, s.tex0)
        GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit)
        set(GL11.GL_TEXTURE_2D, s.tex1)
        GL13.glActiveTexture(s.activeTex)
    }

    private fun set(cap: Int, on: Boolean) {
        if (on) GL11.glEnable(cap) else GL11.glDisable(cap)
    }
}


