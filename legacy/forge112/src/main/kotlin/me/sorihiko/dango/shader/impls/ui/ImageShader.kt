package me.sorihiko.dango.shader.impls.ui

import me.sorihiko.dango.shader.GlShader
import me.sorihiko.dango.ui.Size

import org.lwjgl.BufferUtils
import org.lwjgl.util.vector.Matrix4f

class ImageShader : GlShader(
    "ui/image.vert",
    "ui/image.frag"
){
    private val buffer = BufferUtils.createFloatBuffer(16)

    override fun uniformsFields(): Array<String> {
        return arrayOf(
            "u_Transform",
            "u_ScreenSize",
            "u_Texture",
            "u_Opacity",
        )
    }

    fun setup(mat4: Matrix4f, screenSize: Size, opacity: Float){
        buffer.clear()
        mat4.store(buffer)
        buffer.flip()
        setUniformMat4("u_Transform", buffer)

        setUniform2f("u_ScreenSize", screenSize.width, screenSize.height)
        setUniform1i("u_Texture", 0)
        setUniform1f("u_Opacity", opacity)
    }
}

