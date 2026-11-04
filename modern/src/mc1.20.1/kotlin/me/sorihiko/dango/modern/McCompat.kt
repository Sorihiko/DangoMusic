package me.sorihiko.dango.modern

import com.mojang.blaze3d.platform.InputConstants

import me.sorihiko.dango.platform.PlayerAction

import me.sorihiko.dango.platform.Keys

import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.KeyMapping

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import org.joml.Matrix4fc
import org.lwjgl.opengl.GL30

/** 这个版本的 GlStateManager 在 platform 包里 */
internal typealias GlStateManager = com.mojang.blaze3d.platform.GlStateManager

/** 同步原版 GlStateManager 记的混合 / 颜色写入状态（各版本参数不一样） */
internal object GlCache {
    fun blend(on: Boolean) {
        if (on) GlStateManager._enableBlend() else GlStateManager._disableBlend()
    }

    fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) {
        GlStateManager._colorMask(r, g, b, a)
    }
}

/**
 * 1.20.1 专用的几处写法（同一件事在不同 Minecraft 版本里 API 不一样）。
 * 每个版本在 src/mc<版本>/ 里放一份，构建时按版本选用，对外的样子完全一样。
 */
internal object McCompat {
    /** 游戏（包括其他模组）已经加载完：加载画面已经消失 */
    fun gameLoaded(mc: Minecraft): Boolean = mc.overlay == null

    /** 是不是在自己开的、看不见的 OpenGL 环境里画（只有 26.2 选 Vulkan 时的试验会这样） */
    val offscreenGl: Boolean get() = false

    /** 世界这一轮画完（只有选 Vulkan 时有事做） */
    fun endWorld() {}

    /** 1.21.11 / 26.x 才用：HUD 先画到贴图上。这个版本界面是直接画的，HUD 在 [hudOverlay] 里画 */
    fun renderHudLayer(block: () -> Unit) {}

    /** 原版画完暗角：先把原版排好的界面画掉，再直接画我们的 HUD（这样暗角压不到它） */
    fun hudOverlay(graphics: Any, block: () -> Unit) {
        (graphics as GuiGraphics).flush()
        try {
            block()
        } finally {
            // 我们用自己的 VAO 画过：让原版下次重新绑定它的顶点缓冲（它记着"已经绑好了"会跳过），
            // 不然后面的准星、快捷栏、按钮背景都画不出来
            com.mojang.blaze3d.vertex.BufferUploader.invalidate()
        }
    }

    fun mainMenuNowPlaying(graphics: Any) {
        (graphics as GuiGraphics).flush()
        me.sorihiko.dango.DangoClient.renderMainMenuNowPlaying()
    }

    // 原版全景资源由游戏管理，资源包也会生效；不提交主菜单按钮/文字。
    private val titlePanoramaField by lazy {
        net.minecraft.client.gui.screens.TitleScreen::class.java.declaredFields.single {
            it.type == net.minecraft.client.renderer.PanoramaRenderer::class.java
        }.apply { isAccessible = true }
    }
    private val menuOverlay = net.minecraft.resources.ResourceLocation("minecraft", "textures/gui/title/background/panorama_overlay.png")

    fun renderMenuBackdrop(parent: Screen?): Boolean {
        val mc = Minecraft.getInstance()
        if (mc.level != null || parent !is net.minecraft.client.gui.screens.TitleScreen) return false
        val state = GlState()

        try {
            mc.mainRenderTarget.bindWrite(true)
            Gl.viewport(0, 0, mc.window.width, mc.window.height)
            Gl.scissor(false)
            Gl.colorMask(true, true, true, true)
            // CubeMap 依赖调用者关深度测试；其 BufferUploader 缓存不能跨自绘 VAO 使用。
            Gl.depthTest(false)
            com.mojang.blaze3d.vertex.BufferUploader.invalidate()
            val panorama = titlePanoramaField.get(parent) as net.minecraft.client.renderer.PanoramaRenderer
            panorama.render(mc.deltaFrameTime, 1f)
            // 与主菜单一致的覆盖层，避免切换时亮度突变。
            val graphics = GuiGraphics(mc, mc.renderBuffers().bufferSource())
            graphics.blit(menuOverlay, 0, 0, mc.window.guiScaledWidth, mc.window.guiScaledHeight, 0f, 0f, 16, 128, 16, 128)
            graphics.flush()
        } finally {
            state.restore()
            // 恢复实际 VAO 后也让原版下次重新绑定，不能保留全景的缓存。
            com.mojang.blaze3d.vertex.BufferUploader.invalidate()
        }
        return true
    }

    fun createPlayerKey(action: PlayerAction): KeyMapping =
        KeyMapping(action.translationKey, gameBindingKey(action.defaultKey).value, "key.categories.dangomusic")

    fun gameBindingKey(code: Int): InputConstants.Key = when {
        code == Keys.NONE -> InputConstants.UNKNOWN
        code < 0 -> InputConstants.Type.MOUSE.getOrCreate(code + 100)
        else -> InputConstants.Type.KEYSYM.getOrCreate(KeyMap.toGlfw(code))
    }

    fun commonBindingKey(key: InputConstants.Key): Int =
        if (key.type == InputConstants.Type.MOUSE) Keys.mouse(key.value) else KeyMap.fromGlfw(key.value)


    /** 把原版的视图矩阵临时换成单位矩阵（1.20.1：PoseStack） */
    fun pushIdentityModelView() {
        val mv = RenderSystem.getModelViewStack()
        mv.pushPose()
        mv.setIdentity()
        RenderSystem.applyModelViewMatrix()
    }

    fun popModelView() {
        RenderSystem.getModelViewStack().popPose()
        RenderSystem.applyModelViewMatrix()
    }

    /** 当前的投影矩阵（[Fixed] 算 MVP 用）：这些版本直接问原版要 */
    fun projection(): Matrix4f = RenderSystem.getProjectionMatrix()

    /** 换成自己的正交投影（画屏幕上的东西用），原版的字也跟着用它 */
    fun pushScreenProjection(m: Matrix4f) {
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(m, VertexSorting.ORTHOGRAPHIC_Z)
    }

    fun popScreenProjection() {
        RenderSystem.restoreProjectionMatrix()
    }

    /** 世界画完、开始画我们的东西之前（这些版本当前帧缓冲就是主画面，不用做什么） */
    fun beginWorld() {}

    /** 这些版本直接问 RenderSystem 要投影（在 CPU 上），不用抄 */
    fun captureWorldProjection(projection: Matrix4fc) {}

    /** 绑定帧缓冲（这些版本原版不记帧缓冲，直接绑） */
    fun bindFramebuffer(target: Int, id: Int) {
        Imm.flush()
        GL30.glBindFramebuffer(target, id)
    }

    /** 让原版画一批东西（这些版本原版直接画在当前绑定的帧缓冲上，不用做什么） */
    inline fun vanillaDraw(block: () -> Unit) = block()

    /** 每次画之前（这些版本不用做什么） */
    fun beforeDraw() {}

    fun cameraXRot(): Float = camera().xRot
    fun cameraYRot(): Float = camera().yRot
    fun cameraPosition(): Vec3 = camera().position
    private fun camera() = Minecraft.getInstance().gameRenderer.mainCamera

    fun windowHandle(): Long = Minecraft.getInstance().window.window

    /** 鼠标键 [button]（GLFW 编号：0 左、1 右、2 中）是否按着 */
    fun isMouseDown(button: Int): Boolean =
        org.lwjgl.glfw.GLFW.glfwGetMouseButton(windowHandle(), button) == org.lwjgl.glfw.GLFW.GLFW_PRESS

    /** 键盘键（GLFW 键码）是否按着 */
    fun isGlfwKeyDown(key: Int): Boolean =
        org.lwjgl.glfw.GLFW.glfwGetKey(windowHandle(), key) == org.lwjgl.glfw.GLFW.GLFW_PRESS

    /** 原版视图矩阵的当前值（[Fixed] 算 MVP 用） */
    fun modelView(): Matrix4fc = RenderSystem.getModelViewMatrix()

    /** 用原版字体画一段像素字（[pose] 是模型矩阵），画在当前绑定的帧缓冲上 */
    fun drawText(text: String, x: Float, y: Float, argb: Int, pose: Matrix4f) {
        val mc = Minecraft.getInstance()
        val buffers = mc.renderBuffers().bufferSource()
        mc.font.drawInBatch(
            text, x, y, argb, false, pose, buffers,
            Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT,
        )
        // 前面可能刚用自己的 VAO 画过：原版的顶点缓冲缓存作废，重新绑定再画字
        com.mojang.blaze3d.vertex.BufferUploader.invalidate()
        vanillaDraw { buffers.endBatch() }
    }

    /** 点阵字可以攒成一批交给原版（[drawTexts]） */
    val batchesText: Boolean get() = true

    /** 一批点阵字一起交给原版：字都放进同一个缓冲，最后只收尾、换帧缓冲一次 */
    fun drawTexts(items: List<VanillaText>) {
        val mc = Minecraft.getInstance()
        val buffers = mc.renderBuffers().bufferSource()
        for (t in items) {
            mc.font.drawInBatch(
                t.text, t.x, t.y, t.argb, false, t.pose, buffers,
                Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT,
            )
        }
        com.mojang.blaze3d.vertex.BufferUploader.invalidate()
        vanillaDraw { buffers.endBatch() }
    }

    fun currentScreen(): Screen? = Minecraft.getInstance().screen

    fun setScreen(screen: Screen?) {
        Minecraft.getInstance().setScreen(screen)
    }

    /** 能不能用 OpenGL 画（26.x 起可以选 Vulkan，那时我们画不了） */
    val glAvailable: Boolean get() = true

    /** 原版开始画界面之前（只有 26.x 用到） */
    fun beforeGui() {}

    /** 只有参数本身可点击，复制值不包含提示、引号或额外空格。 */
    private fun copyableChatMessage(message: String, copyText: String, copyHint: String): Component {
        val at = message.indexOf(copyText)
        if (copyText.isEmpty() || at < 0) return Component.literal(message)
        val link = Component.literal(copyText).withStyle { style ->
            val clickable = style.withColor(net.minecraft.ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.COPY_TO_CLIPBOARD, copyText))
            if (copyHint.isEmpty()) clickable else clickable.withHoverEvent(net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal(copyHint)))
        }
        return Component.literal(message.substring(0, at)).append(link).append(message.substring(at + copyText.length))
    }

    /** 玩家进入世界后显示本地提示，不发送服务器聊天。 */
    fun showChatMessage(message: String, copyText: String, copyHint: String): Boolean {
        val client = Minecraft.getInstance()
        if (client.player == null) return false
        client.gui.chat.addMessage(copyableChatMessage(message, copyText, copyHint))
        return true
    }

    /** 不能用 OpenGL 时提示玩家（这些版本一定能用） */
    fun notifyNoGl(again: Boolean = false) {}

    /** 新建一张贴图交给原版管理，[abgr] 是逐行的像素 */
    fun createTexture(name: String, w: Int, h: Int, abgr: IntArray): ModernTexture {
        val native = NativeImage(w, h, false)
        for (y in 0 until h) for (x in 0 until w) native.setPixelRGBA(x, y, abgr[y * w + x])
        val texture = DynamicTexture(native)
        val manager = Minecraft.getInstance().textureManager
        val location = manager.register(name, texture)
        return ModernTexture({ texture.id }, { manager.release(location) })
    }
}

/** 界面基类：把原版界面的各种事件统一成简单的几个方法（1.20.1 的滚轮事件只有一个方向） */
abstract class CompatScreen(title: Component) : Screen(title) {

    /**
     * 我们自己画的输入框在打字时，让一个看不见的原版输入框也拿到焦点：
     * IMBlocker 这类模组只在原版输入框有焦点时才打开系统输入法，否则只能打英文（#19）。
     * 按键和打字照旧由我们自己处理（下面的事件都不往下传），这个输入框不显示、也收不到输入。
     */
    private var imeSink: EditBox? = null

    /** 关界面时还在打字：先让隐藏的原版输入框失去焦点，不然原版以为还在打字，回到游戏后输入法关不掉 */
    override fun removed() {
        if (focused === imeSink) focused = null
        super.removed()
    }

    fun setImeFocus(active: Boolean) {
        val sink = imeSink ?: EditBox(font, 0, 0, 1, 1, Component.empty()).also { imeSink = it }
        if (active) {
            if (sink !in children()) addWidget(sink)
            if (focused !== sink) {
                focused = sink
                me.sorihiko.dango.DangoLogger.info("Dango Music: text input on (system IME allowed)")
            }
            // IMBlocker 只认"最近画过"的输入框：它在原版画输入框时（会问一次 isFocused）记下时间。
            // 隐藏的输入框不画，就每帧在我们画界面时替它问一次，让 IMBlocker 当它在屏幕上
            sink.isFocused
        } else if (focused === sink) {
            focused = null
            me.sorihiko.dango.DangoLogger.info("Dango Music: text input off")
        }
    }

    abstract fun onRender()
    abstract fun onMouseClicked(button: Int)
    abstract fun onMouseDragged()
    abstract fun onMouseReleased()
    abstract fun onScroll(delta: Double)
    abstract fun onKeyPressed(glfwKey: Int)
    abstract fun onKeyReleased(glfwKey: Int)
    abstract fun onCharTyped(c: Char)

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.flush()   // 先把原版排队的东西画掉，再画我们自己的
        onRender()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        onMouseClicked(button)
        return true
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        onMouseDragged()
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        onMouseReleased()
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        onKeyPressed(keyCode)
        return true
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        onKeyReleased(keyCode)
        return true
    }

    override fun charTyped(c: Char, modifiers: Int): Boolean {
        onCharTyped(c)
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        onScroll(delta)
        return true
    }
}
