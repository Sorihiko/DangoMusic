package me.sorihiko.dango.modern

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.font.FontManager
import me.sorihiko.dango.font.UiFont
import me.sorihiko.dango.shader.ShaderManager
import me.sorihiko.dango.shader.impls.QuadShader
import me.sorihiko.dango.shader.impls.ui.FontShader
import me.sorihiko.dango.shader.impls.ui.GlassBlurShader
import me.sorihiko.dango.shader.impls.ui.GlassShader
import me.sorihiko.dango.shader.impls.ui.ImageShader
import me.sorihiko.dango.shader.impls.ui.ShapeShader

/** 高版本平台层自己的 GL 资源：着色器和菜单字体（第一次游戏 tick 时初始化） */
object ModernGl {
    val shaderManager by lazy { ShaderManager(DangoMusic.instance.eventManager) }
    val fontManager = FontManager()

    private val fontCache = mutableMapOf<String, UiFont>()
    fun font(path: String): UiFont = fontCache.getOrPut(path) { fontManager.get(path) }

    private var glReady = false

    /** 第一次游戏 tick 时调用（这时 OpenGL 已经可用）：编译着色器、加载字体 */
    fun init() {
        if (glReady) return
        glReady = true

        shaderManager.register(QuadShader())
        shaderManager.register(ShapeShader())
        shaderManager.register(FontShader())
        shaderManager.register(ImageShader())
        shaderManager.register(GlassShader())
        shaderManager.register(GlassBlurShader())
        shaderManager.load()

        fontManager.register("Inter-Regular-cut.ttf")         // 第一个注册的是默认字体（找不到指定字体时用它）
        fontManager.register("Inter-Medium-cut.ttf")
        fontManager.register("Inter-SemiBold-cut.ttf")
        fontManager.register("InterDisplay-Bold-cut.ttf")
        fontManager.load()
    }
}
