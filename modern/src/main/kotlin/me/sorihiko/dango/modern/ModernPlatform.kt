package me.sorihiko.dango.modern

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.platform.ListenerEnvironment
import me.sorihiko.dango.platform.Platform
import me.sorihiko.dango.platform.PlayerPose
import me.sorihiko.dango.platform.PlayerKeyBinding

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.SoundType
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.TitleScreen

import java.nio.file.Path

/** 高版本的游戏接口（Fabric 和 Forge 共用）。键码在这里和 GLFW 的编号互相转换（26.3 起再由 McCompat 换成 SDL 的），共用代码只看到统一键码 */
object ModernPlatform : Platform {

    private val mc: Minecraft get() = Minecraft.getInstance()

    // .minecraft/config/dangomusic（Fabric 和 Forge 都是这个位置，不依赖任何一边的 API）
    override val configDir: Path get() = mc.gameDirectory.toPath().resolve("config").resolve(DangoMusic.MOD_ID)

    override val playerName: String get() = mc.user.name

    override val languageCode: String? get() = mc.options?.languageCode

    override val windowWidth: Int get() = mc.window.width
    override val windowHeight: Int get() = mc.window.height

    // GLFW 给的是窗口坐标，换算成帧缓冲像素（高 DPI 屏幕上两者不一样）
    override val mouseX: Int
        get() = (mc.mouseHandler.xpos() * mc.window.width / mc.window.screenWidth.coerceAtLeast(1)).toInt()
    override val mouseY: Int
        get() = (mc.mouseHandler.ypos() * mc.window.height / mc.window.screenHeight.coerceAtLeast(1)).toInt()

    override fun isMouseDown(button: Int): Boolean = McCompat.isMouseDown(button)

    override fun isKeyDown(key: Int): Boolean {
        if (key in -100..-1) return isMouseDown(key + 100)
        val glfw = KeyMap.toGlfw(key)
        return glfw > 0 && glfwKey(glfw)
    }

    override fun syncKeyBindings(bindings: List<PlayerKeyBinding>) = ModernKeyBindings.sync(bindings)

    private fun glfwKey(key: Int): Boolean = McCompat.isGlfwKeyDown(key)

    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    // Mac 上是 Command 键（和原版 Screen.hasControlDown 一样）
    override val ctrlDown: Boolean
        get() = if (isMac) glfwKey(GlfwKeys.GLFW_KEY_LEFT_SUPER) || glfwKey(GlfwKeys.GLFW_KEY_RIGHT_SUPER)
        else glfwKey(GlfwKeys.GLFW_KEY_LEFT_CONTROL) || glfwKey(GlfwKeys.GLFW_KEY_RIGHT_CONTROL)

    override var clipboard: String
        get() = mc.keyboardHandler.clipboard
        set(value) {
            mc.keyboardHandler.clipboard = value
        }

    override val inGame: Boolean get() = McCompat.currentScreen() == null && mc.level != null

    override val inMainMenu: Boolean get() = McCompat.currentScreen() is TitleScreen

    override val chatOpen: Boolean get() = McCompat.currentScreen() is ChatScreen

    override val guiScale: Int get() = mc.window.guiScale.toInt()

    override val fps: Int get() = mc.fps

    override val fov: Float get() = mc.options.fov().get().toFloat()

    override val pingMs: Int
        get() {
            val p = mc.player ?: return 0
            if (mc.hasSingleplayerServer()) return 0
            return mc.connection?.getPlayerInfo(p.uuid)?.latency ?: 0
        }

    override val skyDarken: Int? get() = mc.level?.getSkyDarken()

    override val environment: ListenerEnvironment?
        get() {
            val p = mc.player ?: return null
            val level = mc.level ?: return null
            val eye = BlockPos.containing(p.x, p.eyeY, p.z)
            // 闭塞程度：往 14 个方向各看 ENCLOSURE_RANGE 格，碰到实心方块（不算空气和水）的比例
            var hits = 0
            for (d in RAY_DIRECTIONS) {
                for (step in 1..ENCLOSURE_RANGE) {
                    val state = level.getBlockState(BlockPos.containing(p.x + d[0] * step, p.eyeY + d[1] * step, p.z + d[2] * step))
                    if (!state.isAir && state.fluidState.isEmpty) {
                        hits++
                        break
                    }
                }
            }
            val rain = when {
                !level.isRainingAt(eye) -> 0f
                level.isThundering -> 1f
                else -> 0.5f
            }
            return ListenerEnvironment(p.isUnderWater, level.canSeeSky(eye), hits / RAY_DIRECTIONS.size.toFloat(), rain)
        }

    private val probe = BlockPos.MutableBlockPos()
    private val absorptionCache = java.util.IdentityHashMap<net.minecraft.world.level.block.state.BlockState, Float>()

    override fun soundAbsorption(x: Int, y: Int, z: Int): Float {
        val level = mc.level ?: return -1f
        probe.set(x, y, z)
        val state = level.getBlockState(probe)
        return absorptionCache.getOrPut(state) {
            if (state.isAir || !state.fluidState.isEmpty || state.getCollisionShape(level, probe).isEmpty) -1f
            else soundAbsorptionOf(state.soundType)
        }
    }

    override fun openAudioOutput(sampleRate: Int, channels: Int): me.sorihiko.dango.music.AudioOutput? =
        GameAudioOutput.open(sampleRate, channels)

    override val player: PlayerPose?
        get() {
            val p = mc.player ?: return null
            return PlayerPose(p.x, p.eyeY, p.z, p.yRot, p.xRot)
        }

    override fun openMusicScreen() {
        if (McCompat.glAvailable) McCompat.setScreen(ModernMusicScreen(McCompat.currentScreen())) else McCompat.notifyNoGl(again = true)
    }
}

/**
 * 方块按脚步声的材质分几类，吸音系数参考常见建筑材料（中频）：
 * 石头、砖约 2~3%，金属 4%，玻璃 5%，木头约 12%，泥土、草约 30%，沙砾 35%，苔藓 45%，雪 50%，羊毛约 70%
 */
private fun soundAbsorptionOf(sound: SoundType): Float = when (sound) {
    SoundType.STONE, SoundType.DEEPSLATE, SoundType.TUFF, SoundType.CALCITE, SoundType.BASALT,
    SoundType.NETHER_BRICKS, SoundType.NETHERRACK -> 0.03f
    SoundType.METAL, SoundType.COPPER -> 0.04f
    SoundType.GLASS -> 0.05f
    SoundType.WOOD, SoundType.BAMBOO_WOOD, SoundType.CHERRY_WOOD, SoundType.NETHER_WOOD -> 0.12f
    SoundType.GRASS, SoundType.ROOTED_DIRT, SoundType.MUD -> 0.3f
    SoundType.GRAVEL, SoundType.SAND, SoundType.SOUL_SAND -> 0.35f
    SoundType.MOSS -> 0.45f
    SoundType.SNOW, SoundType.POWDER_SNOW -> 0.5f
    SoundType.WOOL -> 0.7f
    else -> 0.1f
}

/** 环境音效看周围闭塞程度时，每个方向最多看几格 */
private const val ENCLOSURE_RANGE = 6

/** 上下左右前后 + 8 个斜对角 */
private val RAY_DIRECTIONS: List<DoubleArray> = run {
    val d = 0.57735
    listOf(
        doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0),
        doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0),
        doubleArrayOf(d, d, d), doubleArrayOf(d, d, -d), doubleArrayOf(-d, d, d), doubleArrayOf(-d, d, -d),
        doubleArrayOf(d, -d, d), doubleArrayOf(d, -d, -d), doubleArrayOf(-d, -d, d), doubleArrayOf(-d, -d, -d),
    )
}
