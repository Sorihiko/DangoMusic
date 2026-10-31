package me.sorihiko.dango.shader.impls.glow

import me.sorihiko.dango.shader.GlShader
class GlowCompositeShader : GlShader(
    "outline/base.vert",
    "glow/composite.frag",
) {
    override fun uniformsFields(): Array<String> {
        return arrayOf("u_Glow", "u_Original", "u_Intensity", "u_Whiteness")
    }

    fun setup(intensity: Float, whiteness: Float) {
        setUniform1i("u_Glow", 0)
        setUniform1i("u_Original", 1)
        setUniform1f("u_Intensity", intensity)
        setUniform1f("u_Whiteness", whiteness)
    }
}


