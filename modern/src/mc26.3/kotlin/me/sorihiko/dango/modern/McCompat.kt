package me.sorihiko.dango.modern

import me.sorihiko.dango.platform.PlayerAction

import me.sorihiko.dango.platform.Keys

import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.KeyMapping

import me.sorihiko.dango.DangoLogger

import com.mojang.blaze3d.ProjectionType
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.backend.opengl.DirectStateAccess
import com.mojang.renderpearl.backend.opengl.FrameBufferAttachment
import com.mojang.renderpearl.backend.opengl.FrameBufferCache
import com.mojang.renderpearl.backend.opengl.GlBuffer
import com.mojang.renderpearl.backend.opengl.GlTexture
import com.mojang.renderpearl.backend.opengl.GlTextureView
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.font.TextRenderable
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL33

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer

/** 26.3 起渲染层改名叫 renderpearl，GlStateManager 搬到了 renderpearl.backend.opengl */
internal typealias GlStateManager = com.mojang.renderpearl.backend.opengl.GlStateManager

/** 同步原版 GlStateManager 记的混合 / 颜色写入状态（26.x 起按每个颜色输出分开记） */
internal object GlCache {
    /** 原版最多记 8 个颜色输出 */
    private const val TARGETS = 8

    fun blend(on: Boolean) {
        for (i in 0 until TARGETS) {
            if (on) GlStateManager._enableBlend(i) else GlStateManager._disableBlend(i)
        }
    }

    fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) {
        val mask = (if (r) 1 else 0) or (if (g) 2 else 0) or (if (b) 4 else 0) or (if (a) 8 else 0)
        GlStateManager._colorMask(mask)
    }
}

/**
 * 26.3 专用的几处写法（同一件事在不同 Minecraft 版本里 API 不一样）。
 * 每个版本在 src/mc<版本>/ 里放一份，构建时按版本选用，对外的样子完全一样。
 *
 * 26.x 和 1.21.11 比又变了几处：
 * - 画东西分成"先收集、再统一画"两步，原版不再提供"马上画一段字"的方法，像素字改成我们自己画（字形贴图还是原版的）；
 * - 深度范围改成 0 到 1（以前是 -1 到 1），我们自己的正交投影要跟着换；
 * - 显卡接口可以选 Vulkan，选了 Vulkan 时我们没法用 OpenGL 画，只放音乐、提示一下。
 */
internal object McCompat {
    /** 游戏（包括其他模组）已经加载完 */
    fun gameLoaded(mc: Minecraft): Boolean = mc.isGameLoadFinished

    /**
     * 选了 Vulkan 时：我们自己开了一个看不见的 OpenGL 环境，东西照旧用 OpenGL 画到贴图上，
     * 读回内存，再交给原版（Vulkan）贴到屏幕上。见 [OffscreenGl]、[VulkanBridge]、[ScreenLayer]。
     */
    var offscreenGl = false
        private set

    fun mainMenuNowPlaying(graphics: Any) {
        screenLayer.submit(graphics as GuiGraphicsExtractor)
    }

    /** 世界画完时：HUD 先画到自己的贴图上（[hudLayer]）；Vulkan 下直接画进世界那一层 */
    fun renderHudLayer(block: () -> Unit) {
        if (offscreenGl) block() else hudLayer.render(block)
    }

    /** 原版收集到暗角之后：把 HUD 那一层交给原版（Vulkan 下交的是整个世界那一层，含歌词），暗角压不到它 */
    fun hudOverlay(graphics: Any, block: () -> Unit) {
        if (mc.level == null) return
        val g = graphics as GuiGraphicsExtractor
        if (offscreenGl) {
            if (VulkanBridge.world.hasContent) VulkanBridge.world.submit(g)
        } else if (hudLayer.hasContent) {
            hudLayer.submit(g)
        }
    }

    // 原版全景资源由游戏管理，资源包也会生效；不提交主菜单按钮/文字。
    private val menuCube by lazy { net.minecraft.client.renderer.CubeMap(net.minecraft.resources.Identifier.withDefaultNamespace("textures/gui/title/background/panorama")) }

    private val menuOverlay by lazy {
        val id = Identifier.withDefaultNamespace("textures/gui/title/background/panorama_overlay.png")
        ModernTexture({ (mc.textureManager.getTexture(id).getTexture() as GlTexture).glId() }, {})
    }

    fun renderMenuBackdrop(parent: Screen?, advance: Boolean = true): Boolean {
        if (mc.level != null) return false
        if (offscreenGl) {
            // 原版的全景在 Vulkan 那边：我们用同样的图自己画一份
            VulkanBridge.drawMenuBackdrop(advance)
            return true
        }
        val state = GlState()
        val savedProjection = RenderSystem.getProjectionMatrixBuffer()
        val savedProjectionType = RenderSystem.getProjectionType()
        try {
            bindFramebuffer(GL30.GL_FRAMEBUFFER, mainFramebuffer())
            Gl.viewport(0, 0, mc.window.width, mc.window.height)
            Gl.scissor(false)
            Gl.colorMask(true, true, true, true)
            val spin = MenuPanoramaRotation.advance(mc.gameRenderer.panorama(), (if (advance) mc.deltaTracker.realtimeDeltaTicks else 0f), mc.options.panoramaSpeed().get())
            menuCube.render(10f, spin)
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
        code < 0 -> InputConstants.Type.MOUSE.getOrCreate(when (val button = code + 100) { 0 -> 1; 1 -> 3; 2 -> 2; else -> button + 1 })
        else -> InputConstants.Type.KEYBOARD.getOrCreate(SdlKeys.fromGlfw(KeyMap.toGlfw(code)))
    }

    fun commonBindingKey(key: InputConstants.Key): Int =
        if (key.type == InputConstants.Type.MOUSE) Keys.mouse(when (key.value) { 1 -> 0; 3 -> 1; 2 -> 2; else -> key.value - 1 }) else KeyMap.fromGlfw(SdlKeys.toGlfw(key.value))


    private val mc: Minecraft get() = Minecraft.getInstance()

    /** 把原版的视图矩阵临时换成单位矩阵 */
    fun pushIdentityModelView() {
        RenderSystem.getModelViewStack().pushMatrix().identity()
    }

    fun popModelView() {
        RenderSystem.getModelViewStack().popMatrix()
    }

    /** 原版视图矩阵的当前值（[Fixed] 算 MVP 用） */
    fun modelView(): Matrix4fc = RenderSystem.getModelViewStack()

    // ------------------------------------------------------------ OpenGL / Vulkan

    /** 原版显卡设备里真正干活的那个（OpenGL 的是 GlDevice，这个类不公开，只能反射拿） */
    /** 26.3 起 GpuDevice 是接口，实现类（FrontendGpuDevice）里有个 backend 字段；按运行时的类去找 */
    private var backendField: Field? = null
    private var backendSearched = false

    private fun backend(): Any? {
        val device = RenderSystem.tryGetDevice() ?: return null
        if (!backendSearched) {
            backendSearched = true
            backendField = try {
                var c: Class<*>? = device.javaClass
                var f: Field? = null
                while (c != null && f == null) {
                    f = c.declaredFields.firstOrNull { it.name == "backend" }
                    c = c.superclass
                }
                f?.apply { isAccessible = true } ?: error("no backend field in ${device.javaClass.name}")
            } catch (t: Throwable) {
                DangoLogger.error("Dango Music: cannot find GPU backend: {}", t)
                null
            }
        }
        return backendField?.get(device)
    }

    private var glChecked: Boolean? = null

    /** 能不能用 OpenGL 画（视频设置里选了 Vulkan 时不能） */
    val glAvailable: Boolean
        get() {
            glChecked?.let { return it }
            val backend = backend() ?: return true   // 还没建好，先当能用
            if (backend.javaClass.name.startsWith("com.mojang.renderpearl.backend.opengl.")) {
                glChecked = true
                return true
            }
            // Vulkan：试着自己开一个看不见的 OpenGL 环境
            offscreenGl = OffscreenGl.create()
            if (!offscreenGl) DangoLogger.warn("Dango Music: graphics backend is {} and no OpenGL is available, lyrics and the player screen are disabled", backend.javaClass.name)
            glChecked = offscreenGl
            return offscreenGl
        }

    private var noGlNotified = false

    private var lastNoGlNotice = 0L

    /**
     * 不能用 OpenGL 时在聊天栏提示：进游戏时提示一次；[again] = true（按了打开播放器的键）时再提示，
     * 不然按 M 没反应，玩家不知道为什么（两秒内只提示一次，免得连按刷屏）
     */
    fun notifyNoGl(again: Boolean = false) {
        if (noGlNotified && !again) return
        val player = mc.player ?: return
        val now = System.currentTimeMillis()
        if (again && now - lastNoGlNotice < 2000) return
        noGlNotified = true
        lastNoGlNotice = now
        player.sendSystemMessage(
            Component.literal(
                "[Dango Music] Lyrics and the music screen only work with OpenGL for now. " +
                    "Switch the graphics API to OpenGL in Video Settings (music still plays).",
            ),
        )
    }

    // ------------------------------------------------------------ 投影矩阵

    /** 世界的投影矩阵：每帧世界画完时从显存里读出来（含视角摇晃，和原版画世界用的完全一样） */
    private val worldProjection = Matrix4f()

    /** 我们自己换上的正交投影（画屏幕上的东西用），可以嵌套 */
    private val screenProjections = ArrayDeque<Matrix4f>()

    /** 换投影前原版的投影（显存里的那份），换回去用 */
    private val savedProjections = ArrayDeque<Pair<GpuBufferSlice?, ProjectionType>>()

    /** 每一层嵌套各用一块显存放正交投影 */
    private val orthoBuffers = ArrayList<ProjectionMatrixBuffer>()

    /**
     * 原版是不是把深度范围设成了 0 到 1（显卡支持时 26.x 会这样设）。
     * 这时 -1 到 0 之间的东西会被裁掉，我们按 -1 到 1 算的正交投影要换算一下。
     */
    private val zeroToOneDepth: Boolean by lazy {
        val mode = GL11.glGetInteger(GL_CLIP_DEPTH_MODE)
        GL11.glGetError()   // 不支持时会留下一个错误，清掉
        mode == GL_ZERO_TO_ONE
    }

    private const val GL_CLIP_DEPTH_MODE = 0x935D
    private const val GL_ZERO_TO_ONE = 0x935F

    /** 当前的投影矩阵（[Fixed] 算 MVP 用） */
    fun projection(): Matrix4f = screenProjections.lastOrNull() ?: worldProjection

    /** 换成自己的正交投影（画屏幕上的东西用） */
    fun pushScreenProjection(m: Matrix4f) {
        val fixed = Matrix4f(m)
        // z' = (z + w) / 2：把 -1..1 换成 0..1
        if (zeroToOneDepth) fixed.set(Matrix4f().translation(0f, 0f, 0.5f).scale(1f, 1f, 0.5f).mul(m))
        val depth = screenProjections.size
        while (orthoBuffers.size <= depth) {
            orthoBuffers.add(ProjectionMatrixBuffer("Dango Music ortho ${orthoBuffers.size}"))
        }
        screenProjections.addLast(fixed)
        // 自己的 OpenGL 环境里画时，原版（Vulkan）的投影不用跟着换
        if (offscreenGl) return
        savedProjections.addLast(RenderSystem.getProjectionMatrixBuffer() to RenderSystem.getProjectionType())
        RenderSystem.setProjectionMatrix(orthoBuffers[depth].getBuffer(Matrix4f(fixed)), ProjectionType.ORTHOGRAPHIC)
    }

    fun popScreenProjection() {
        screenProjections.removeLastOrNull()
        if (offscreenGl) return
        val (buffer, type) = savedProjections.removeLastOrNull() ?: return
        if (buffer != null) RenderSystem.setProjectionMatrix(buffer, type)
    }

    /** 原版 GlBuffer 里存 OpenGL 编号的字段（private，只能反射拿） */
    private val bufferHandle: Field? by lazy {
        try {
            val fields = GlBuffer::class.java.declaredFields
            (fields.firstOrNull { it.name == "handle" }
                ?: fields.first { it.type == Int::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers) })
                .apply { isAccessible = true }
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music: cannot read projection buffer: {}", t)
            null
        }
    }

    private val matrixData = BufferUtils.createFloatBuffer(16)

    /** 从显存里读出原版当前的投影矩阵；读不到时用镜头的投影（不含视角摇晃） */
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
        dest.set(mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix)
    }

    // ------------------------------------------------------------ 帧缓冲

    private val directStateAccess: Method? by lazy { backendMethod("directStateAccess") }
    private val frameBufferCache: Method? by lazy { backendMethod("frameBufferCache") }

    private fun backendMethod(name: String): Method? = try {
        val backend = backend() ?: error("no GPU device")
        backend.javaClass.getDeclaredMethod(name).apply { isAccessible = true }
    } catch (t: Throwable) {
        DangoLogger.error("Dango Music: cannot find {}: {}", name, t)
        null
    }

    private val attachments = ArrayList<FrameBufferAttachment?>(1)

    /**
     * 主画面的帧缓冲编号。要和原版自己画东西时用的是同一个（原版按"颜色 + 深度贴图视图"从缓存里取帧缓冲）；
     * 编号一致，原版才会认为"已经绑好了"。
     */
    fun mainFramebuffer(): Int {
        // Vulkan：没有原版的主画面，玻璃的背景从 VulkanBridge.scene 取（读回来的游戏画面，或我们画的全景）
        if (offscreenGl) {
            val scene = VulkanBridge.scene
            if (scene.framebuffer == 0) scene.ensure(mc.window.width, mc.window.height)
            return scene.framebuffer
        }
        val target = mc.gameRenderer.mainRenderTarget()
        val backend = backend()
        val dsa = directStateAccess?.invoke(backend) as? DirectStateAccess
        val cache = frameBufferCache?.invoke(backend) as? FrameBufferCache
        val color = target.getColorTextureView() as? GlTextureView
        if (dsa == null || cache == null || color == null) {
            // 拿不到原版的缓存：退回到"当前绑着的那个"
            return GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        }
        attachments.clear()
        attachments.add(color)
        return cache.getFbo(dsa, attachments, target.getDepthTextureView() as? GlTextureView)
    }

    /** 自己建的一个空帧缓冲，只用来让原版的帧缓冲缓存"失效"一下 */
    private var dummyFramebuffer = 0

    /** 绑定帧缓冲，同时让原版 GlStateManager 记的值和实际一致 */
    fun bindFramebuffer(target: Int, id: Int) {
        Imm.flush()
        if (dummyFramebuffer == 0) dummyFramebuffer = GL30.glGenFramebuffers()
        GlStateManager._glBindFramebuffer(target, dummyFramebuffer)
        GlStateManager._glBindFramebuffer(target, id)
    }

    /** 世界画完、开始画我们的东西之前：记下世界的投影，切到主画面 */
    fun beginWorld() {
        readProjection(worldProjection)
        if (offscreenGl) {
            VulkanBridge.beginWorld()
            return
        }
        bindFramebuffer(GL30.GL_FRAMEBUFFER, mainFramebuffer())
        val target = mc.gameRenderer.mainRenderTarget()
        Gl.viewport(0, 0, target.width, target.height)
    }

    /** 世界这一轮画完（Vulkan 下读回、交给原版） */
    fun endWorld() {
        if (offscreenGl) VulkanBridge.endWorld()
    }

    /**
     * 每次画之前：原版给贴图单元绑了"采样器"，它会盖掉贴图自己的过滤 / 边缘设置，先解开。
     * （原版自己每次画都会重新绑，不用还原）
     */
    fun beforeDraw() {
        for (unit in 0 until 4) GL33.glBindSampler(unit, 0)
    }

    // ------------------------------------------------------------ 像素字

    /** 收集原版字形的四边形：每个字形 4 个点（x, y, u, v）+ 颜色 + 用哪张贴图 */
    private object GlyphQuads : VertexConsumer {
        var xs = FloatArray(64)
        var ys = FloatArray(64)
        var us = FloatArray(64)
        var vs = FloatArray(64)
        var colors = IntArray(16)
        var textures = arrayOfNulls<GpuTextureView>(16)
        var vertices = 0
        var currentTexture: GpuTextureView? = null

        fun reset() {
            vertices = 0
        }

        private fun ensure() {
            if (vertices < xs.size) return
            val n = xs.size * 2
            xs = xs.copyOf(n); ys = ys.copyOf(n); us = us.copyOf(n); vs = vs.copyOf(n)
            colors = colors.copyOf(n / 4)
            textures = textures.copyOf(n / 4)
        }

        override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
            ensure()
            xs[vertices] = x
            ys[vertices] = y
            if (vertices % 4 == 0) textures[vertices / 4] = currentTexture
            vertices++
            return this
        }

        private val last get() = vertices - 1

        override fun setColor(r: Int, g: Int, b: Int, a: Int): VertexConsumer =
            setColor((a shl 24) or (r shl 16) or (g shl 8) or b)

        override fun setColor(color: Int): VertexConsumer {
            if (last >= 0 && last % 4 == 0) colors[last / 4] = color
            return this
        }

        override fun setUv(u: Float, v: Float): VertexConsumer {
            if (last >= 0) { us[last] = u; vs[last] = v }
            return this
        }

        override fun setUv1(u: Int, v: Int): VertexConsumer = this
        override fun setUv3(u: Float, v: Float): VertexConsumer = this
        override fun setUv2(u: Int, v: Int): VertexConsumer = this
        override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer = this
        override fun setLineWidth(width: Float): VertexConsumer = this
    }

    private val identity = Matrix4f()
    private val savedPose = Matrix4f()
    private val savedColor = FloatArray(4)

    /**
     * 用原版字体画一段像素字（[pose] 是模型矩阵），画在当前绑定的帧缓冲上。
     * 字形和位置都照原版算（Font.prepareText），只是最后用我们自己的着色器画出来。
     */
    fun drawText(text: String, x: Float, y: Float, argb: Int, pose: Matrix4f) {
        if (offscreenGl) {
            // 原版的字形贴图在 Vulkan 那边，读不到：用同样的点阵图自己画，每个字的宽度问原版
            val font = VulkanBridge.pixelFont()
            if (font != null) font.draw(text, x, y, argb, pose) { mc.font.width(it) }
            else PixelTextFallback.draw(text, x, y, argb, pose, mc.font.width(text))
            return
        }
        // 字是直接用原版字形画的，不经过 Imm：也算一次绘制，HUD 那一层才知道"画了东西"、要交给原版
        if (text.isNotEmpty()) Imm.countDraw()
        GlyphQuads.reset()
        val prepared = mc.font.prepareText(text, x, y, argb, false, 0)
        prepared.visit(object : Font.GlyphVisitor {
            override fun acceptRenderable(renderable: TextRenderable) {
                GlyphQuads.currentTexture = renderable.textureView()
                renderable.render(identity, GlyphQuads, 0xF000F0, true)
            }
        })
        val quads = GlyphQuads.vertices / 4
        if (quads == 0) return

        savedPose.set(Fixed.pose)
        System.arraycopy(Fixed.color, 0, savedColor, 0, 4)
        Fixed.pose.set(pose)
        Gl.activeTexture(GL13_TEXTURE0)
        // 这一轮画图共用：原版字形贴图的设置一直保持到这一轮画完，中间的字才能合成一批
        val borrowedTextures = BorrowedGlyphTextures.forPass()
        try {
            for (q in 0 until quads) {
                val view = GlyphQuads.textures[q] as? GlTextureView ?: continue
                val texture = view.texture() as? GlTexture ?: continue
                borrowedTextures.bind(texture.glId(), texture.getFormat() == GpuFormat.R8_UNORM)

                val c = GlyphQuads.colors[q]
                Fixed.color((c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f, (c ushr 24) / 255f)
                Imm.begin()
                for (k in 0 until 4) {
                    val i = q * 4 + k
                    Imm.tex(GlyphQuads.us[i], GlyphQuads.vs[i])
                    Imm.vertex(GlyphQuads.xs[i], GlyphQuads.ys[i])
                }
                Imm.endFixed()

            }
        } finally {
            Fixed.pose.set(savedPose)
            Fixed.color(savedColor[0], savedColor[1], savedColor[2], savedColor[3])
        }
    }

    private const val GL13_TEXTURE0 = 0x84C0

    // ------------------------------------------------------------ 镜头 / 窗口 / 界面

    fun cameraXRot(): Float = mc.gameRenderer.mainCamera().xRot()
    fun cameraYRot(): Float = mc.gameRenderer.mainCamera().yRot()
    fun cameraPosition(): Vec3 = mc.gameRenderer.mainCamera().position()

    fun windowHandle(): Long = mc.window.handle()

    // ------------------------------------------------------------ 键盘 / 鼠标（26.3 起是 SDL）

    /**
     * 鼠标键 [button]（GLFW 编号：0 左、1 右、2 中）是否按着：直接问 SDL。
     * （原版 MouseHandler 也记着三个键，但开着界面时它不更新，拖滑块时会一直是"没按"）
     */
    fun isMouseDown(button: Int): Boolean {
        val sdlButton = when (button) { 0 -> 1; 1 -> 3; 2 -> 2; else -> button + 1 }
        if (sdlButton !in 1..31) return false
        val mask = 1 shl (sdlButton - 1)
        val state = org.lwjgl.sdl.SDLMouse.SDL_GetMouseState(null as java.nio.FloatBuffer?, null as java.nio.FloatBuffer?)
        return state and mask != 0
    }

    /** 键盘键（GLFW 键码）是否按着：换成 SDL 的键位编号再问原版 */
    fun isGlfwKeyDown(key: Int): Boolean {
        val sdl = SdlKeys.fromGlfw(key)
        return sdl > 0 && InputConstants.isKeyDown(sdl)
    }

    /** 26.x 起当前界面归 Gui 管 */
    fun currentScreen(): Screen? = mc.gui.screen()

    fun setScreen(screen: Screen?) {
        mc.gui.setScreen(screen)
    }

    /** 原版开始画界面之前：如果开着我们的界面，先把它画到它自己的贴图上 */
    fun beforeGui() {
        val screen = currentScreen()
        if (screen is CompatScreen) {
            screenLayer.render { screen.onRender() }
        } else if (screen is net.minecraft.client.gui.screens.TitleScreen && me.sorihiko.dango.music.MusicPlayer.active) {
            screenLayer.render { me.sorihiko.dango.DangoClient.renderMainMenuNowPlaying() }
        }
    }

    // ------------------------------------------------------------ 贴图

    private var textureCount = 0

    /** 新建一张贴图交给原版管理，[abgr] 是逐行的像素 */
    fun createTexture(name: String, w: Int, h: Int, abgr: IntArray): ModernTexture {
        if (offscreenGl) return GlCanvas.texture(w, h, abgr)
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
 * 选了 Vulkan 时：自己开一个看不见的 OpenGL 窗口（只要它的 OpenGL 环境），我们的东西照旧用 OpenGL 画。
 * 原版在 Vulkan 下不用 OpenGL，所以这个环境建好后一直留在渲染线程上就行。26.3 起窗口归 SDL 管，用 SDL 建。
 */
internal object OffscreenGl {
    private var window = 0L

    fun create(): Boolean {
        if (window != 0L) return true
        return try {
            org.lwjgl.sdl.SDLVideo.SDL_GL_ResetAttributes()
            org.lwjgl.sdl.SDLVideo.SDL_GL_SetAttribute(org.lwjgl.sdl.SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 3)
            org.lwjgl.sdl.SDLVideo.SDL_GL_SetAttribute(org.lwjgl.sdl.SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 3)
            org.lwjgl.sdl.SDLVideo.SDL_GL_SetAttribute(org.lwjgl.sdl.SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, org.lwjgl.sdl.SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE)
            org.lwjgl.sdl.SDLVideo.SDL_GL_SetAttribute(org.lwjgl.sdl.SDLVideo.SDL_GL_CONTEXT_FLAGS, org.lwjgl.sdl.SDLVideo.SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG)
            val w = org.lwjgl.sdl.SDLVideo.SDL_CreateWindow("Dango Music (offscreen)", 16, 16,
                org.lwjgl.sdl.SDLVideo.SDL_WINDOW_OPENGL or org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN)
            if (w == 0L) {
                DangoLogger.warn("Dango Music: cannot create an offscreen OpenGL window: {}", org.lwjgl.sdl.SDLError.SDL_GetError() ?: "?")
                return false
            }
            val context = org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext(w)
            if (context == 0L) {
                DangoLogger.warn("Dango Music: cannot create an offscreen OpenGL context: {}", org.lwjgl.sdl.SDLError.SDL_GetError() ?: "?")
                org.lwjgl.sdl.SDLVideo.SDL_DestroyWindow(w)
                return false
            }
            org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent(w, context)
            GL.createCapabilities()
            window = w
            DangoLogger.info("Dango Music: drawing through an offscreen OpenGL context on Vulkan: {} / {}",
                GL11.glGetString(GL11.GL_RENDERER) ?: "?", GL11.glGetString(GL11.GL_VERSION) ?: "?")
            true
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music: offscreen OpenGL failed: {}", t)
            false
        }
    }
}

/**
 * Vulkan 时和原版打交道的部分：
 * - 世界画完后我们画的歌词 / Song card / 灵动岛放在 [world] 这一层，原版画 HUD 时先把它贴上（在 HUD 下面）；
 * - 玻璃的背景（[scene]）：在游戏里时把原版画好的画面读回来（晚一帧，模糊之后看不出来），在主菜单时自己画全景。
 */
internal object VulkanBridge {
    private val mc: Minecraft get() = Minecraft.getInstance()

    val world = ScreenLayer("world_layer")
    val scene = GlCanvas()

    private var worldDraws = 0

    /** 世界画完：开始往 [world] 上画 */
    fun beginWorld() {
        val window = mc.window
        updateScene()
        world.beginOffscreen(window.width, window.height)
        worldDraws = Imm.draws
        Gl.premultipliedTarget = true
    }

    /** 画完：有东西才读回、交给原版；顺便为下一帧的玻璃读一份游戏画面 */
    fun endWorld() {
        Gl.premultipliedTarget = false
        world.endOffscreen(Imm.draws != worldDraws)
        if (OffscreenHints.glassUsed) requestScene()
        OffscreenHints.glassUsed = false
    }

    // ------------------------------------------------------------ 玻璃背景：游戏画面

    private var readBuffer: GpuBuffer? = null
    private var readW = 0
    private var readH = 0
    @Volatile private var pending = false
    @Volatile private var fresh = false
    private var copied: ByteBuffer? = null
    private var copiedW = 0
    private var copiedH = 0

    /** 让原版把画好的画面拷一份到内存（拷完时回调，下一帧再用） */
    private fun requestScene() {
        if (pending) return
        val tex = mc.gameRenderer.mainRenderTarget().getColorTexture() ?: return
        val w = tex.getWidth(0)
        val h = tex.getHeight(0)
        if (w <= 0 || h <= 0) return
        if (readBuffer == null || readW != w || readH != h) {
            readBuffer?.close()
            readBuffer = RenderSystem.getDevice().createBuffer(
                { "Dango Music scene readback" }, GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_MAP_READ, w.toLong() * h * 4,
            )
            readW = w
            readH = h
        }
        val buffer = readBuffer!!
        pending = true
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(tex, buffer, 0L, {
            try {
                buffer.map(true, false).use { view ->
                    val src = view.data()
                    var dst = copied
                    if (dst == null || dst.capacity() < w * h * 4) dst = BufferUtils.createByteBuffer(w * h * 4).also { copied = it }
                    // 原版（Vulkan）拷出来的行序和 OpenGL 的贴图一样（从最下面一行开始），直接放
                    src.limit(w * h * 4).position(0)
                    dst!!.clear()
                    dst.put(src)
                    dst.clear()
                    copiedW = w
                    copiedH = h
                    fresh = true
                }
            } catch (t: Throwable) {
                DangoLogger.warn("Dango Music: reading the game image failed: {}", t.toString())
            } finally {
                pending = false
            }
        }, 0)
    }

    /** 读回来的游戏画面写进 [scene] */
    private fun updateScene() {
        if (!fresh) return
        val data = copied ?: return
        scene.ensure(copiedW, copiedH)
        data.position(0).limit(copiedW * copiedH * 4)
        scene.write(data)
        fresh = false
    }

    // ------------------------------------------------------------ 玻璃背景：主菜单全景

    private var panoramaFaces: List<ModernTexture>? = null
    private var panoramaOverlay: ModernTexture? = null
    private var panoramaLoaded = false

    /** 读一张资源图（资源包也会生效），返回 (ABGR 像素, 宽, 高) */
    private fun readImage(id: Identifier): Triple<IntArray, Int, Int>? = try {
        mc.resourceManager.getResource(id).orElse(null)?.open()?.use { stream ->
            val img = javax.imageio.ImageIO.read(stream) ?: return@use null
            val w = img.width
            val h = img.height
            val argb = img.getRGB(0, 0, w, h, null, 0, w)
            // ARGB → ABGR（交换红和蓝）
            for (i in argb.indices) {
                val c = argb[i]
                argb[i] = (c and 0xFF00FF00.toInt()) or ((c and 0xFF) shl 16) or ((c shr 16) and 0xFF)
            }
            Triple(argb, w, h)
        }
    } catch (t: Throwable) {
        DangoLogger.warn("Dango Music: cannot load {}: {}", id, t.toString())
        null
    }

    private fun loadImage(path: String): ModernTexture? =
        readImage(Identifier.withDefaultNamespace(path))?.let { (pixels, w, h) -> GlCanvas.texture(w, h, pixels) }

    // ------------------------------------------------------------ 像素字

    private var pixelFont: PixelFont? = null
    private var pixelFontLoaded = false

    /** 原版点阵字（第一次用时按原版字体定义建好） */
    fun pixelFont(): PixelFont? {
        if (pixelFontLoaded) return pixelFont
        pixelFontLoaded = true
        pixelFont = try {
            val json = mc.resourceManager.getResource(Identifier.withDefaultNamespace("font/include/default.json")).orElse(null)
                ?.open()?.use { it.readBytes().toString(Charsets.UTF_8) }
            if (json == null) null else PixelFont.load(json) { file ->
                val id = Identifier.parse(file)
                readImage(Identifier.fromNamespaceAndPath(id.namespace, "textures/" + id.path))
            }
        } catch (t: Throwable) {
            DangoLogger.warn("Dango Music: cannot load the pixel font: {}", t.toString())
            null
        }
        return pixelFont
    }

    /** 主菜单：在 [scene] 上画全景（[advance] 时让全景接着转；原版自己在画全景时不要推进，免得转两倍快） */
    fun drawPanoramaScene(advance: Boolean) {
        if (!panoramaLoaded) {
            panoramaLoaded = true
            val faces = (0 until 6).mapNotNull { loadImage("textures/gui/title/background/panorama_$it.png") }
            panoramaFaces = if (faces.size == 6) faces else null
            panoramaOverlay = loadImage("textures/gui/title/background/panorama_overlay.png")
        }
        val faces = panoramaFaces ?: return
        val window = mc.window
        scene.ensure(window.width, window.height)
        val spin = MenuPanoramaRotation.advance(mc.gameRenderer.panorama(),
            if (advance) mc.deltaTracker.realtimeDeltaTicks else 0f, mc.options.panoramaSpeed().get())
        val state = GlState()
        try {
            scene.begin()
            GlPanorama.draw(faces, panoramaOverlay, 10f, spin, scene.width, scene.height)
        } finally {
            state.restore()
        }
    }

    /** 我们的播放器在主菜单打开时：先在 [scene] 上画全景，再整张拷到当前要画的地方当背景 */
    fun drawMenuBackdrop(advance: Boolean) {
        val target = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        drawPanoramaScene(advance)
        if (scene.framebuffer == 0) return
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, scene.framebuffer)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target)
        GL30.glBlitFramebuffer(0, 0, scene.width, scene.height, 0, 0, scene.width, scene.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST)
        McCompat.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, scene.framebuffer)
        McCompat.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target)
    }

    /** 我们的界面开始画之前：准备好玻璃背景 */
    fun prepareScreenScene() {
        if (mc.level == null) drawPanoramaScene(false) else updateScene()
    }
}

/**
 * 原版界面是"先收集要画什么、最后统一画"：收集的时候（extractRenderState）还不能画。
 * 所以收集时只交一张"和窗口一样大的贴图"给原版；等原版开始画界面之前（[McCompat.beforeGui]），
 * 再把我们的界面画到这张贴图上，原版随后按顺序把它贴出来，就在 HUD 上面了。
 *
 * 选 Vulkan 时，原版的贴图在 Vulkan 那边：我们画到自己 OpenGL 环境里的 [canvas] 上，画完读回内存，再写进原版的贴图。
 */
internal class ScreenLayer(path: String) {
    private val id = Identifier.fromNamespaceAndPath("dangomusic", path)
    private var texture: LayerTexture? = null
    private var framebuffer = 0
    private val canvas = GlCanvas()
    private var width = 0
    private var height = 0
    private val transparent = floatArrayOf(0f, 0f, 0f, 0f)
    /** 贴图里现在有没有东西（没有就不交给原版；Vulkan 下也不用每帧读回） */
    var hasContent = false
        private set
    private var state: GlState? = null

    private class LayerTexture(w: Int, h: Int) : AbstractTexture() {
        init {
            val device = RenderSystem.getDevice()
            val t: GpuTexture = device.createTexture(
                "Dango Music layer",
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, w, h, 1, 1,
            )
            texture = t
            textureView = device.createTextureView(t)
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        }

        val glId: Int get() = (texture as GlTexture).glId()
        val gpuTexture: GpuTexture get() = texture!!
    }

    private fun ensure(w: Int, h: Int) {
        if (w == width && h == height && texture != null) return
        val manager = Minecraft.getInstance().textureManager
        if (texture != null) manager.release(id)
        val t = LayerTexture(w, h)
        manager.register(id, t)
        texture = t
        hasContent = false

        if (McCompat.offscreenGl) {
            canvas.ensure(w, h)
        } else {
            if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers()
            val previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer)
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, t.glId, 0)
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous)
        }
        width = w
        height = h
    }

    /** 收集阶段：把整张贴图作为一个界面元素交给原版（一个像素对一个像素；OpenGL 贴图从下往上存，v 反过来取） */
    fun submit(graphics: GuiGraphicsExtractor) {
        val window = Minecraft.getInstance().window
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return
        ensure(w, h)
        val pose = graphics.pose()
        pose.pushMatrix()
        val s = 1f / window.guiScale
        pose.scale(s, s)
        graphics.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, id, 0, 0, 0f, 1f, w, h, 1, -1, 1, 1, -1)
        pose.popMatrix()
    }

    /** 原版画界面之前：把我们的界面画到贴图上 */
    fun render(block: () -> Unit) {
        val window = Minecraft.getInstance().window
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return
        if (McCompat.offscreenGl) {
            VulkanBridge.prepareScreenScene()
            beginOffscreen(w, h)
            val draws = Imm.draws
            try {
                Gl.premultipliedTarget = true
                block()
            } finally {
                Gl.premultipliedTarget = false
                endOffscreen(Imm.draws != draws)
            }
            return
        }
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
            val draws = Imm.draws
            block()
            hasContent = Imm.draws != draws
        } finally {
            Gl.premultipliedTarget = false
            state.restore()
        }
    }

    /** Vulkan：开始往我们自己的画布上画（读：玻璃背景；写：画布） */
    fun beginOffscreen(w: Int, h: Int) {
        ensure(w, h)
        state = GlState()
        McCompat.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, McCompat.mainFramebuffer())
        canvas.begin()
    }

    /** Vulkan：画完。画了东西就读回来写进原版的贴图；这次没画、上次画了，就把原版的贴图清空一次 */
    fun endOffscreen(drewSomething: Boolean) {
        try {
            val t = texture ?: return
            if (drewSomething) {
                val pixels = canvas.readBack()
                RenderSystem.getDevice().createCommandEncoder().writeToTexture(t.gpuTexture, pixels, 0, 0, 0, 0, width, height)
                hasContent = true
            } else if (hasContent) {
                RenderSystem.getDevice().createCommandEncoder().clearColorTexture(t.gpuTexture, Vector4f(0f, 0f, 0f, 0f))
                hasContent = false
            }
        } finally {
            state?.restore()
            state = null
        }
    }
}

/** 主界面 / 主菜单 Now playing 用的那一层 */
private val screenLayer = ScreenLayer("screen_layer")

/** Song card、灵动岛、Now playing 那一层（世界画完时画好，原版画完暗角再交出去） */
private val hudLayer = ScreenLayer("hud_layer")

/** 界面基类：把原版界面的各种事件统一成简单的几个方法（26.3 起键盘是 SDL 的编号，这里换成 GLFW 的） */
abstract class CompatScreen(title: Component) : Screen(title) {

    /**
     * 我们自己画的输入框在打字时，让一个看不见的原版输入框也拿到焦点：
     * IMBlocker 这类模组只在原版输入框有焦点时才打开系统输入法，否则只能打英文（#19）。
     * 按键和打字照旧由我们自己处理（下面的事件都不往下传），这个输入框不显示、也收不到输入。
     */
    private var imeSink: EditBox? = null

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
            // 原版输入框失去焦点时会关掉 SDL 文字输入（只记一个使用者），拿回来，界面开着就一直打开
            Minecraft.getInstance().textInputManager().startTextInput(this)
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

    /**
     * 26.3 起打字走 SDL 的"文字输入"：要先打开它，charTyped 才会收到字（中文输入法也是靠它）。
     * 原版只在输入框获得焦点时打开；我们的界面里有搜索框，界面开着就一直打开。
     */
    override fun added() {
        super.added()
        Minecraft.getInstance().textInputManager().startTextInput(this)
    }

    override fun removed() {
        // 关界面时还在打字：先让隐藏的原版输入框失去焦点，不然原版以为还在打字，回到游戏后输入法关不掉
        if (focused === imeSink) focused = null
        Minecraft.getInstance().textInputManager().stopTextInput(this)
        super.removed()
    }

    /** 不要原版的模糊 / 变暗背景（玻璃自己会模糊背后的画面） */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {}

    /** 收集阶段只交一张贴图，真正的内容在原版画界面之前才画（见 ScreenLayer） */
    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        screenLayer.submit(graphics)
        // 别删：26.x 的 IMBlocker 只认收集阶段问过 isFocused 的输入框
        imeSink?.let { if (focused === it) it.isFocused }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        // 26.3 起鼠标键是 SDL 的编号（1 左、2 中、3 右），共用代码认的是 GLFW 的（0 左、1 右、2 中）
        val button = when (event.button()) {
            1 -> 0
            3 -> 1
            2 -> 2
            else -> event.button() - 1
        }
        onMouseClicked(button)
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
        // 26.3 起 key() 是 SDL 的键位编号，换成共用代码认的 GLFW 键码
        onKeyPressed(SdlKeys.toGlfw(event.key()))
        return true
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        onKeyReleased(SdlKeys.toGlfw(event.key()))
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        for (c in Character.toChars(event.codepoint())) onCharTyped(c)
        return true
    }
}

/**
 * GLFW 键码和 SDL 键位编号（scancode，就是原版 InputConstants.KEY_* 的数值）互相转换。
 * 共用代码和设置文件里用的都是 GLFW 键码（再往前是 1.8.9 的统一键码，见 KeyMap），只有 26.3 起要换成 SDL 的。
 */
internal object SdlKeys {
    private val toSdl = HashMap<Int, Int>()
    private val toGlfw = HashMap<Int, Int>()

    private fun map(glfw: Int, sdl: Int) {
        toSdl[glfw] = sdl
        toGlfw[sdl] = glfw
    }

    init {
        for (i in 0..25) map(GlfwKeys.GLFW_KEY_A + i, 4 + i)                 // A-Z
        for (i in 1..9) map(GlfwKeys.GLFW_KEY_0 + i, 29 + i)                 // 1-9
        map(GlfwKeys.GLFW_KEY_0, 39)
        map(GlfwKeys.GLFW_KEY_ENTER, 40); map(GlfwKeys.GLFW_KEY_ESCAPE, 41)
        map(GlfwKeys.GLFW_KEY_BACKSPACE, 42); map(GlfwKeys.GLFW_KEY_TAB, 43); map(GlfwKeys.GLFW_KEY_SPACE, 44)
        map(GlfwKeys.GLFW_KEY_MINUS, 45); map(GlfwKeys.GLFW_KEY_EQUAL, 46)
        map(GlfwKeys.GLFW_KEY_LEFT_BRACKET, 47); map(GlfwKeys.GLFW_KEY_RIGHT_BRACKET, 48)
        map(GlfwKeys.GLFW_KEY_BACKSLASH, 49); map(GlfwKeys.GLFW_KEY_SEMICOLON, 51)
        map(GlfwKeys.GLFW_KEY_APOSTROPHE, 52); map(GlfwKeys.GLFW_KEY_GRAVE_ACCENT, 53)
        map(GlfwKeys.GLFW_KEY_COMMA, 54); map(GlfwKeys.GLFW_KEY_PERIOD, 55); map(GlfwKeys.GLFW_KEY_SLASH, 56)
        map(GlfwKeys.GLFW_KEY_CAPS_LOCK, 57)
        for (i in 0..11) map(GlfwKeys.GLFW_KEY_F1 + i, 58 + i)               // F1-F12
        map(GlfwKeys.GLFW_KEY_PRINT_SCREEN, 70); map(GlfwKeys.GLFW_KEY_SCROLL_LOCK, 71); map(GlfwKeys.GLFW_KEY_PAUSE, 72)
        map(GlfwKeys.GLFW_KEY_INSERT, 73); map(GlfwKeys.GLFW_KEY_HOME, 74); map(GlfwKeys.GLFW_KEY_PAGE_UP, 75)
        map(GlfwKeys.GLFW_KEY_DELETE, 76); map(GlfwKeys.GLFW_KEY_END, 77); map(GlfwKeys.GLFW_KEY_PAGE_DOWN, 78)
        map(GlfwKeys.GLFW_KEY_RIGHT, 79); map(GlfwKeys.GLFW_KEY_LEFT, 80)
        map(GlfwKeys.GLFW_KEY_DOWN, 81); map(GlfwKeys.GLFW_KEY_UP, 82)
        map(GlfwKeys.GLFW_KEY_NUM_LOCK, 83)
        map(GlfwKeys.GLFW_KEY_KP_DIVIDE, 84); map(GlfwKeys.GLFW_KEY_KP_MULTIPLY, 85)
        map(GlfwKeys.GLFW_KEY_KP_SUBTRACT, 86); map(GlfwKeys.GLFW_KEY_KP_ADD, 87); map(GlfwKeys.GLFW_KEY_KP_ENTER, 88)
        for (i in 1..9) map(GlfwKeys.GLFW_KEY_KP_0 + i, 88 + i)              // 小键盘 1-9
        map(GlfwKeys.GLFW_KEY_KP_0, 98); map(GlfwKeys.GLFW_KEY_KP_DECIMAL, 99)
        map(GlfwKeys.GLFW_KEY_MENU, 101); map(GlfwKeys.GLFW_KEY_KP_EQUAL, 103)
        for (i in 0..11) map(GlfwKeys.GLFW_KEY_F13 + i, 104 + i)             // F13-F24
        map(GlfwKeys.GLFW_KEY_LEFT_CONTROL, 224); map(GlfwKeys.GLFW_KEY_LEFT_SHIFT, 225)
        map(GlfwKeys.GLFW_KEY_LEFT_ALT, 226); map(GlfwKeys.GLFW_KEY_LEFT_SUPER, 227)
        map(GlfwKeys.GLFW_KEY_RIGHT_CONTROL, 228); map(GlfwKeys.GLFW_KEY_RIGHT_SHIFT, 229)
        map(GlfwKeys.GLFW_KEY_RIGHT_ALT, 230); map(GlfwKeys.GLFW_KEY_RIGHT_SUPER, 231)
    }

    /** GLFW → SDL；没有对应的键返回 -1 */
    fun fromGlfw(glfw: Int): Int = toSdl[glfw] ?: -1

    /** SDL → GLFW；没有对应的键返回 -1 */
    fun toGlfw(sdl: Int): Int = toGlfw[sdl] ?: -1
}
