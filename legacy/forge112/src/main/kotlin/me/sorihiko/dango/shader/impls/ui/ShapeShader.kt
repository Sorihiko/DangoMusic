package me.sorihiko.dango.shader.impls.ui

import me.sorihiko.dango.shader.GlShader
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Size

import org.lwjgl.BufferUtils
import org.lwjgl.util.vector.Matrix4f

class ShapeShader : GlShader(
    "ui/shape.vert",
    "ui/shape.frag",
) {
    private val buffer = BufferUtils.createFloatBuffer(16)

    override fun uniformsFields(): Array<String> {
        return arrayOf(
            "u_Transform",
            "u_ScreenSize",
            "u_Bounds",
            "u_Radius",
            "u_FillColor",
            "u_BorderWidth",
            "u_BorderColor",
            "u_ShadowColor",
            "u_ShadowSize",
            "u_ShadowOffset",
            "u_Opacity",
        )
    }

    fun setup(
        mat4: Matrix4f,
        screenSize: Size,
        bounds: LayoutBounds,
        style: ShapeStyle,
        opacity: Float
    ) {
        buffer.clear()
        mat4.store(buffer)
        buffer.flip()
        setUniformMat4("u_Transform", buffer)

        setUniform2f("u_ScreenSize", screenSize.width, screenSize.height)
        setUniform4f("u_Bounds", bounds.x, bounds.y, bounds.width, bounds.height)
        setUniform1f("u_Radius", style.radius)
        setUniformColor("u_FillColor", style.fillColor)
        setUniform1f("u_BorderWidth", style.borderWidth)
        setUniformColor("u_BorderColor", style.borderColor)
        setUniformColor("u_ShadowColor", style.shadow.color)
        setUniform1f("u_ShadowSize", style.shadow.size)
        setUniform2f("u_ShadowOffset", style.shadow.offset.x, style.shadow.offset.y)
        setUniform1f("u_Opacity", opacity)
    }
}

