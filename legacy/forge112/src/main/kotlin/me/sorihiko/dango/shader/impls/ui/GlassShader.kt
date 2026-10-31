package me.sorihiko.dango.shader.impls.ui

import me.sorihiko.dango.shader.GlShader
import me.sorihiko.dango.ui.GlassStyle
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.Size

import org.lwjgl.BufferUtils
import org.lwjgl.util.vector.Matrix4f

class GlassShader : GlShader(
    "ui/glass.vert",
    "ui/glass.frag",
) {
    private val buffer = BufferUtils.createFloatBuffer(16)

    override fun uniformsFields(): Array<String> {
        return arrayOf(
            "u_Transform",
            "u_ScreenSize",
            "u_Bounds",
            "u_Radius",
            "u_Soft",
            "u_Frost",
            "u_FrostMix",
            "u_Tint",
            "u_MaxLuma",
            "u_Saturation",
            "u_Refraction",
            "u_Bevel",
            "u_Dispersion",
            "u_Highlight",
            "u_Opacity",
        )
    }

    fun setup(
        mat4: Matrix4f,
        screenSize: Size,
        bounds: LayoutBounds,
        style: GlassStyle,
        opacity: Float
    ) {
        buffer.clear()
        mat4.store(buffer)
        buffer.flip()
        setUniformMat4("u_Transform", buffer)

        setUniform2f("u_ScreenSize", screenSize.width, screenSize.height)
        setUniform4f("u_Bounds", bounds.x, bounds.y, bounds.width, bounds.height)
        setUniform1f("u_Radius", style.radius)
        setUniform1i("u_Soft", 0)
        setUniform1i("u_Frost", 1)
        setUniform1f("u_FrostMix", style.frost)
        setUniformColor("u_Tint", style.tint)
        setUniform1f("u_MaxLuma", style.maxLuma)
        setUniform1f("u_Saturation", style.saturation)
        setUniform1f("u_Refraction", style.refraction)
        setUniform1f("u_Bevel", style.bevel)
        setUniform1f("u_Dispersion", style.dispersion)
        setUniform1f("u_Highlight", style.highlight)
        setUniform1f("u_Opacity", opacity)
    }
}


