package me.sorihiko.dango.shader.impls.ui

import me.sorihiko.dango.shader.GlShader
import me.sorihiko.dango.ui.Size
import me.sorihiko.dango.utils.Color

import org.lwjgl.BufferUtils
import org.lwjgl.util.vector.Matrix4f

class FontShader : GlShader(
    "ui/font.vert",
    "ui/font.frag",
) {
    private val buffer = BufferUtils.createFloatBuffer(16)

    override fun uniformsFields(): Array<String> {
        return arrayOf(
            "u_Transform",
            "u_ScreenSize",
            "u_Font",
            "u_Color",
            "u_Opacity"
        )
    }

    fun setup(mat4: Matrix4f, screenSize: Size, color: Color, opacity: Float){
        buffer.clear()
        mat4.store(buffer)
        buffer.flip()
        setUniformMat4("u_Transform", buffer)

        setUniform2f("u_ScreenSize", screenSize.width, screenSize.height)
        setUniform1i("u_Font", 0)
        setUniformColor("u_Color", color)
        setUniform1f("u_Opacity", opacity)
    }
}

