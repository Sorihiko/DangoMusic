package me.sorihiko.dango.shader.impls.glow

import me.sorihiko.dango.shader.GlShader
class GlowDownsampleShader : GlShader(
    "outline/base.vert",
    "glow/downsample.frag",
) {
    override fun uniformsFields(): Array<String> {
        return arrayOf("u_Texture", "u_TexelSize", "u_Premultiply")
    }

    fun setup(sourceWidth: Int, sourceHeight: Int, premultiply: Boolean) {
        setUniform1i("u_Texture", 0)
        setUniform2f("u_TexelSize", 1f / sourceWidth, 1f / sourceHeight)
        setUniform1f("u_Premultiply", if (premultiply) 1f else 0f)
    }
}


