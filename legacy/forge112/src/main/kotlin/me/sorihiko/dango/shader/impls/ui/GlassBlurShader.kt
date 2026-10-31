package me.sorihiko.dango.shader.impls.ui

import me.sorihiko.dango.shader.GlShader
class GlassBlurShader : GlShader(
    "quad.vert",
    "ui/glass_blur.frag",
) {
    override fun uniformsFields(): Array<String> {
        return arrayOf(
            "u_Texture",
            "u_Direction",
        )
    }

    fun setup(dx: Float, dy: Float) {
        setUniform1i("u_Texture", 0)
        setUniform2f("u_Direction", dx, dy)
    }
}


