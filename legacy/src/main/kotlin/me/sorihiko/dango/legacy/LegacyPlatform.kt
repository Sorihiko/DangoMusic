package me.sorihiko.dango.legacy

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.platform.Platform
import me.sorihiko.dango.platform.PlayerPose
import me.sorihiko.dango.platform.PlayerKeyBinding

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiChat
import net.minecraft.client.gui.GuiMainMenu
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse

import java.nio.file.Path

/** 1.8.9 的游戏接口。键码本来就是 LWJGL 2 的，不用转换 */
object LegacyPlatform : Platform {

    override val configDir: Path get() = mc.mcDataDir.toPath().resolve("config").resolve(DangoMusic.MOD_ID)

    override val playerName: String get() = mc.session.username

    override val languageCode: String? get() = mc.gameSettings?.language

    override val windowWidth: Int get() = mc.displayWidth
    override val windowHeight: Int get() = mc.displayHeight

    override val mouseX: Int get() = Mouse.getX()
    override val mouseY: Int get() = mc.displayHeight - Mouse.getY() - 1

    override fun isMouseDown(button: Int): Boolean = Mouse.isButtonDown(button)

    override fun isKeyDown(key: Int): Boolean = try {
        if (key in -100..-1) Mouse.isButtonDown(key + 100) else Keyboard.isKeyDown(key)
    } catch (_: Exception) {
        false
    }

    override fun syncKeyBindings(bindings: List<PlayerKeyBinding>) = LegacyKeyBindings.sync(bindings)

    override val ctrlDown: Boolean get() = GuiScreen.isCtrlKeyDown()

    override var clipboard: String
        get() = GuiScreen.getClipboardString()
        set(value) = GuiScreen.setClipboardString(value)

    override val inGame: Boolean get() = mc.currentScreen == null && mc.theWorld != null

    override val inMainMenu: Boolean get() = mc.currentScreen is GuiMainMenu

    override val chatOpen: Boolean get() = mc.currentScreen is GuiChat

    override val guiScale: Int get() = ScaledResolution(mc).scaleFactor

    override val fps: Int get() = Minecraft.getDebugFPS()

    override val fov: Float get() = mc.gameSettings.fovSetting

    override val pingMs: Int
        get() {
            val p = mc.thePlayer ?: return 0
            if (mc.isSingleplayer) return 0
            return mc.netHandler?.getPlayerInfo(p.uniqueID)?.responseTime ?: 0
        }

    override val skyDarken: Int? get() = mc.theWorld?.skylightSubtracted

    private val absorptionCache = java.util.IdentityHashMap<net.minecraft.block.Block, Float>()

    override fun soundAbsorption(x: Int, y: Int, z: Int): Float {
        val world = mc.theWorld ?: return -1f
        val block = world.getBlockState(net.minecraft.util.BlockPos(x, y, z)).block
        return absorptionCache.getOrPut(block) {
            val material = block.material
            if (material.isLiquid || !material.blocksMovement()) -1f else when (block.stepSound) {
                net.minecraft.block.Block.soundTypeStone, net.minecraft.block.Block.soundTypePiston -> 0.03f
                net.minecraft.block.Block.soundTypeMetal, net.minecraft.block.Block.soundTypeAnvil -> 0.04f
                net.minecraft.block.Block.soundTypeGlass -> 0.05f
                net.minecraft.block.Block.soundTypeWood, net.minecraft.block.Block.soundTypeLadder -> 0.12f
                net.minecraft.block.Block.soundTypeGrass -> 0.3f
                net.minecraft.block.Block.soundTypeGravel, net.minecraft.block.Block.soundTypeSand -> 0.35f
                net.minecraft.block.Block.soundTypeSnow -> 0.5f
                net.minecraft.block.Block.soundTypeCloth -> 0.7f
                else -> 0.1f
            }
        }
    }

    override val environment: me.sorihiko.dango.platform.ListenerEnvironment?
        get() {
            val p = mc.thePlayer ?: return null
            val world = mc.theWorld ?: return null
            val eyeY = p.posY + p.getEyeHeight()
            val eye = net.minecraft.util.BlockPos(p.posX, eyeY, p.posZ)
            // 闭塞程度：往 14 个方向各看 6 格，碰到实心方块（不算空气和液体）的比例
            var hits = 0
            val d = 0.57735
            val dirs = arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0),
                doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0),
                doubleArrayOf(d, d, d), doubleArrayOf(d, d, -d), doubleArrayOf(-d, d, d), doubleArrayOf(-d, d, -d),
                doubleArrayOf(d, -d, d), doubleArrayOf(d, -d, -d), doubleArrayOf(-d, -d, d), doubleArrayOf(-d, -d, -d),
            )
            for (dir in dirs) {
                for (step in 1..6) {
                    val pos = net.minecraft.util.BlockPos(p.posX + dir[0] * step, eyeY + dir[1] * step, p.posZ + dir[2] * step)
                    val block = world.getBlockState(pos).block
                    if (!world.isAirBlock(pos) && !block.material.isLiquid) {
                        hits++
                        break
                    }
                }
            }
            val rain = when {
                !world.isRainingAt(eye) -> 0f
                world.isThundering -> 1f
                else -> 0.5f
            }
            return me.sorihiko.dango.platform.ListenerEnvironment(
                p.isInsideOfMaterial(net.minecraft.block.material.Material.water), world.canSeeSky(eye), hits / dirs.size.toFloat(), rain)
        }

    override val player: PlayerPose?
        get() {
            val p = mc.thePlayer ?: return null
            return PlayerPose(p.posX, p.posY + p.getEyeHeight(), p.posZ, p.rotationYaw, p.rotationPitch)
        }

    override fun openMusicScreen() {
        mc.displayGuiScreen(LegacyMusicScreen(mc.currentScreen))
    }
}
