package me.sorihiko.dango.modern

import com.mojang.blaze3d.platform.InputConstants

import me.sorihiko.dango.platform.PlayerAction

import me.sorihiko.dango.platform.Keys

import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.KeyMapping

import me.sorihiko.dango.DangoLogger

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.opengl.GlBuffer
import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.opengl.GlTextureView
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.TextureFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import org.joml.Matrix4fc
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL33

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** 1.21.5 起 GlStateManager 搬到了 opengl 包 */
internal typealias GlStateManager = com.mojang.blaze3d.opengl.GlStateManager

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
 * 1.21.11 专用的几处写法（同一件事在不同 Minecraft 版本里 API 不一样）。
 * 每个版本在 src/mc<版本>/ 里放一份，构建时按版本选用，对外的样子完全一样。
 *
 * 1.21.5 起原版画东西的方式大改：每次画都自己绑帧缓冲、着色器、采样器，投影矩阵放在显存里（不再能直接读）。
 * 这里把这些差别补齐，让共用代码照旧直接用 OpenGL 画。
 */
internal object McCompat {
    /** 游戏（包括其他模组）已经加载完：加载画面已经消失 */
    fun gameLoaded(mc: Minecraft): Boolean = mc.overlay == null

    /** 是不是在自己开的、看不见的 OpenGL 环境里画（只有 26.2 选 Vulkan 时的试验会这样） */
    val offscreenGl: Boolean get() = false

    /** 世界这一轮画完（只有选 Vulkan 时有事做） */
    fun endWorld() {}

    /** 26.x 才用：HUD 先画到贴图上。这个版本在 [hudOverlay] 里当场画到贴图上再交出去 */
    fun renderHudLayer(block: () -> Unit) {}

    /** 原版画完暗角：把我们的 HUD 画到透明贴图上，作为一个界面元素交给原版（排在暗角后面，暗角压不到它） */
    fun hudOverlay(graphics: Any, block: () -> Unit) {
        ScreenLayer.draw(graphics as GuiGraphics, block)
    }

    fun mainMenuNowPlaying(graphics: Any) {
        ScreenLayer.draw(graphics as GuiGraphics) { me.sorihiko.dango.DangoClient.renderMainMenuNowPlaying() }
    }

    // 原版全景资源由游戏管理，资源包也会生效；不提交主菜单按钮/文字。
    private val menuCube by lazy { net.minecraft.client.renderer.CubeMap(net.minecraft.resources.Identifier.withDefaultNamespace("textures/gui/title/background/panorama")) }

    private val menuOverlay by lazy {
        val id = Identifier.withDefaultNamespace("textures/gui/title/background/panorama_overlay.png")
        ModernTexture({ (mc.textureManager.getTexture(id).getTexture() as GlTexture).glId() }, {})
    }

    fun renderMenuBackdrop(parent: Screen?): Boolean {
        if (mc.level != null) return false
        val state = GlState()
        val savedProjection = RenderSystem.getProjectionMatrixBuffer()
        val savedProjectionType = RenderSystem.getProjectionType()
        try {
            bindFramebuffer(GL30.GL_FRAMEBUFFER, mainFramebuffer())
            Gl.viewport(0, 0, mc.window.width, mc.window.height)
            Gl.scissor(false)
            Gl.colorMask(true, true, true, true)
            val spin = MenuPanoramaRotation.advance(mc.gameRenderer.panorama, mc.deltaTracker.realtimeDeltaTicks, mc.options.panoramaSpeed().get())
            menuCube.render(mc, 10f, spin)
            MenuPanoramaOverlay.draw(menuOverlay, mc.window.width, mc.window.height)
        } finally {
            if (savedProjection != null) RenderSystem.setProjectionMatrix(savedProjection, savedProjectionType)
            state.restore()
        }
        return true
    }

    private val playerKeyCategory by lazy { KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("dangomusic", "controls")) }

    fun createPlayerKey(action: PlayerAction): KeyMapping =
        KeyMapping(action.translationKey, gameBindingKey(action.defaultKey).value, playerKeyCategory)

    fun gameBindingKey(code: Int): InputConstants.Key = when {
        code == Keys.NONE -> InputConstants.UNKNOWN
        code < 0 -> InputConstants.Type.MOUSE.getOrCreate(code + 100)
        else -> InputConstants.Type.KEYSYM.getOrCreate(KeyMap.toGlfw(code))
    }

    fun commonBindingKey(key: InputConstants.Key): Int =
        if (key.type == InputConstants.Type.MOUSE) Keys.mouse(key.value) else KeyMap.fromGlfw(key.value)


    private val mc: Minecraft get() = Minecraft.getInstance()

    /** 把原版的视图矩阵临时换成单位矩阵（原版画字时会读它） */
    fun pushIdentityModelView() {
        RenderSystem.getModelViewStack().pushMatrix().identity()
    }

    fun popModelView() {
        RenderSystem.getModelViewStack().popMatrix()
    }

    // ------------------------------------------------------------ 投影矩阵

    /** 世界的投影矩阵：每帧世界画完时从显存里读出来（含视角摇晃，和原版画世界用的完全一样） */
    private val worldProjection = Matrix4f()

    /** 我们自己换上的正交投影（画屏幕上的东西用），可以嵌套 */
    private val screenProjections = ArrayDeque<Matrix4f>()

    /** 换投影前原版的投影（显存里的那份），换回去用 */
    private val savedProjections = ArrayDeque<Pair<GpuBufferSlice?, ProjectionType>>()

    /** 每一层嵌套各用一块显存放正交投影（同一帧里会换好几次，分开放互不影响） */
    private val orthoBuffers = ArrayList<PerspectiveProjectionMatrixBuffer>()

    /** 当前的投影矩阵（[Fixed] 算 MVP 用） */
    fun projection(): Matrix4f = screenProjections.lastOrNull() ?: worldProjection

    /** 换成自己的正交投影（画屏幕上的东西用），原版的字也跟着用它 */
    fun pushScreenProjection(m: Matrix4f) {
        val depth = screenProjections.size
        while (orthoBuffers.size <= depth) {
            orthoBuffers.add(PerspectiveProjectionMatrixBuffer("Dango Music ortho ${orthoBuffers.size}"))
        }
        screenProjections.addLast(Matrix4f(m))
        savedProjections.addLast(RenderSystem.getProjectionMatrixBuffer() to RenderSystem.getProjectionType())
        RenderSystem.setProjectionMatrix(orthoBuffers[depth].getBuffer(m), ProjectionType.ORTHOGRAPHIC)
    }

    fun popScreenProjection() {
        screenProjections.removeLastOrNull()
        val (buffer, type) = savedProjections.removeLastOrNull() ?: return
        if (buffer != null) RenderSystem.setProjectionMatrix(buffer, type)
    }

    /** 原版 GlBuffer 里存 OpenGL 编号的字段（protected，只能反射拿；按类型找，不依赖字段名） */
    private val bufferHandle: Field? by lazy {
        try {
            GlBuffer::class.java.declaredFields
                .first { it.type == Int::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers) }
                .apply { isAccessible = true }
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music: cannot read projection buffer: {}", t)
            null
        }
    }

    /** 原版这一帧画世界用的投影（含视角摇晃），上传显卡前由 mixin 抄下来 */
    private val capturedProjection = Matrix4f()
    private var projectionCaptured = false
    private var loggedReadback = false

    fun captureWorldProjection(projection: Matrix4fc) {
        capturedProjection.set(projection)
        projectionCaptured = true
    }

    /** 世界的投影：优先用抄下来的；这一帧没抄到才从显存读（读显存要等显卡，慢） */
    private fun loadWorldProjection(dest: Matrix4f) {
        if (projectionCaptured) {
            dest.set(capturedProjection)
            projectionCaptured = false
            return
        }
        if (!loggedReadback) {
            loggedReadback = true
            DangoLogger.warn("Dango Music: world projection not captured, reading it back from the GPU")
        }
        readProjection(dest)
    }

    private val matrixData = BufferUtils.createFloatBuffer(16)

    /** 从显存里读出原版当前的投影矩阵；读不到时按视野自己算一个（不含视角摇晃） */
    private fun readProjection(dest: Matrix4f) {
        val slice = RenderSystem.getProjectionMatrixBuffer()
        val buffer = slice?.buffer() as? GlBuffer
        val field = bufferHandle
        if (slice != null && buffer != null && field != null) {
            val previous = GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER)
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, field.getInt(buffer))
            matrixData.clear()
            for (i in 0 until 16) matrixData.put(i, 0f)
            GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER, slice.offset(), matrixData)
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, previous)
            dest.set(matrixData)
            // 安卓上（MobileGlues 等把 OpenGL 转成 OpenGL ES）没有 glGetBufferSubData，读出来是全 0，
            // 3D 歌词就整个缩没了：读到的不像投影矩阵就改用原版自己存的那份
            if (dest.isFinite && kotlin.math.abs(dest.determinant()) > 1e-20f) return
        }
        dest.set(mc.gameRenderer.getProjectionMatrix(mc.options.fov().get().toFloat()))
    }

    // ------------------------------------------------------------ 帧缓冲

    /**
     * 主画面的帧缓冲编号。要和原版自己画东西时用的是同一个（原版按"贴图视图"建帧缓冲，
     * 和按贴图建的编号不一样；编号一致，原版才会认为"已经绑好了"，发光等效果才能把原版的字也画进去）
     */
    fun mainFramebuffer(): Int {
        val target = mc.mainRenderTarget
        val device = RenderSystem.getDevice() as GlDevice
        return (target.getColorTextureView() as GlTextureView).getFbo(device.directStateAccess(), target.getDepthTexture())
    }

    /** 自己建的一个空帧缓冲，只用来让原版的帧缓冲缓存"失效"一下 */
    private var dummyFramebuffer = 0

    /**
     * 绑定帧缓冲，同时让原版 GlStateManager 记的值和实际一致。
     * 这个版本的原版会记住"现在绑的是哪个"，记的和实际不一样时，它会以为不用重绑，画到错的地方去。
     */
    fun bindFramebuffer(target: Int, id: Int) {
        Imm.flush()
        if (dummyFramebuffer == 0) dummyFramebuffer = GL30.glGenFramebuffers()
        GlStateManager._glBindFramebuffer(target, dummyFramebuffer)
        GlStateManager._glBindFramebuffer(target, id)
    }

    private val savedViewport = IntArray(4)

    /**
     * 让原版画一批东西（原版的字），画到我们当前绑定的帧缓冲上（比如发光画布）。
     *
     * 这个版本的原版每次画都要绑"主画面"，画完还会绑回 0（窗口本身）。
     * 先让它以为主画面已经绑好了（实际绑的是我们的），它就会画在我们的上面；画完再把我们的绑回来。
     */
    fun vanillaDraw(block: () -> Unit) {
        val draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        val read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport)
        bindFramebuffer(GL30.GL_FRAMEBUFFER, mainFramebuffer())   // 原版记的：主画面
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw)     // 实际的：我们的
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read)
        try {
            block()
        } finally {
            bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw)
            bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read)
            Gl.viewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3])
        }
    }

    /** 世界画完、开始画我们的东西之前：记下世界的投影，切到主画面 */
    fun beginWorld() {
        loadWorldProjection(worldProjection)
        bindFramebuffer(GL30.GL_FRAMEBUFFER, mainFramebuffer())
        val target = mc.mainRenderTarget
        Gl.viewport(0, 0, target.width, target.height)
    }

    /**
     * 每次画之前：原版给贴图单元绑了"采样器"，它会盖掉贴图自己的过滤 / 边缘设置，先解开。
     * （原版自己每次画都会重新绑，不用还原）
     */
    fun beforeDraw() {
        for (unit in 0 until 4) GL33.glBindSampler(unit, 0)
    }

    // ------------------------------------------------------------ 镜头 / 窗口

    fun cameraXRot(): Float = mc.gameRenderer.mainCamera.xRot()
    fun cameraYRot(): Float = mc.gameRenderer.mainCamera.yRot()
    fun cameraPosition(): Vec3 = mc.gameRenderer.mainCamera.position()

    fun windowHandle(): Long = mc.window.handle()

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
                .withClickEvent(net.minecraft.network.chat.ClickEvent.CopyToClipboard(copyText))
            if (copyHint.isEmpty()) clickable else clickable.withHoverEvent(net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(copyHint)))
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

    // ------------------------------------------------------------ 贴图

    private var textureCount = 0

    /** 新建一张贴图交给原版管理，[abgr] 是逐行的像素 */
    fun createTexture(name: String, w: Int, h: Int, abgr: IntArray): ModernTexture {
        val native = NativeImage(w, h, false)
        for (y in 0 until h) for (x in 0 until w) native.setPixelABGR(x, y, abgr[y * w + x])
        val texture = DynamicTexture({ name }, native)
        // 原版的贴图名只能用小写字母、数字和 / . _ -
        val path = "dynamic/" + name.lowercase().replace(Regex("[^a-z0-9/._-]"), "_") + "_" + textureCount++
        val id = Identifier.fromNamespaceAndPath("dangomusic", path)
        val manager = mc.textureManager
        manager.register(id, texture)
        return ModernTexture({ (texture.getTexture() as GlTexture).glId() }, { manager.release(id) })
    }
}

/**
 * 1.21.6 起原版界面是"先记下要画什么、最后统一画"：在 render 里直接画的东西会被之后画的 HUD 盖住。
 * 所以先把我们的界面画到一张和窗口一样大的透明贴图上，再把这张贴图作为一个普通的界面元素交给原版，
 * 原版按顺序画，它就在 HUD 上面了。
 */
private object ScreenLayer {
    private val id = Identifier.fromNamespaceAndPath("dangomusic", "screen_layer")
    private var texture: LayerTexture? = null
    private var framebuffer = 0
    private var width = 0
    private var height = 0
    private val transparent = floatArrayOf(0f, 0f, 0f, 0f)

    private class LayerTexture(w: Int, h: Int) : AbstractTexture() {
        init {
            val device = RenderSystem.getDevice()
            val t = device.createTexture(
                "Dango Music screen",
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT,
                TextureFormat.RGBA8, w, h, 1, 1,
            )
            texture = t
            textureView = device.createTextureView(t)
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        }

        val glId: Int get() = (texture as GlTexture).glId()
    }

    private fun ensure(w: Int, h: Int) {
        if (w == width && h == height && texture != null) return
        val manager = Minecraft.getInstance().textureManager
        if (texture != null) manager.release(id)
        val t = LayerTexture(w, h)
        manager.register(id, t)
        texture = t

        if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers()
        val previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer)
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, t.glId, 0)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous)
        width = w
        height = h
    }

    fun draw(graphics: GuiGraphics, block: () -> Unit) {
        val window = Minecraft.getInstance().window
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return
        ensure(w, h)

        val state = GlState()
        try {
            // 读：主画面（玻璃要模糊的背景从这里抓）；写：我们的透明贴图
            McCompat.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, McCompat.mainFramebuffer())
            McCompat.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer)
            Gl.viewport(0, 0, w, h)
            Gl.scissor(false)
            Gl.colorMask(true, true, true, true)
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, transparent)
            Gl.premultipliedTarget = true
            block()
        } finally {
            Gl.premultipliedTarget = false
            state.restore()
        }

        // 整张贴到界面上：一个像素对一个像素（先把界面缩放抵消掉）；OpenGL 贴图是从下往上存的，v 反过来取
        val pose = graphics.pose()
        pose.pushMatrix()
        val s = 1f / window.guiScale
        pose.scale(s, s)
        graphics.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, id, 0, 0, 0f, 1f, w, h, 1, -1, 1, 1, -1)
        pose.popMatrix()
    }
}

/** 界面基类：把原版界面的各种事件统一成简单的几个方法（1.21.9 起鼠标 / 键盘事件都包成了对象） */
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
        val sink = imeSink ?: EditBox(font, 1, 1, Component.empty()).also { imeSink = it }
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

    /** 不要原版的模糊 / 变暗背景（玻璃自己会模糊背后的画面） */
    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {}

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        ScreenLayer.draw(graphics) { onRender() }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        onMouseClicked(event.button())
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        onMouseDragged()
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        onMouseReleased()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        onScroll(scrollY)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        onKeyPressed(event.key())
        return true
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        onKeyReleased(event.key())
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        for (c in Character.toChars(event.codepoint())) onCharTyped(c)
        return true
    }
}
