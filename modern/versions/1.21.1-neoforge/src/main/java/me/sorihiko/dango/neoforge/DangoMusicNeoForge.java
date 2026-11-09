package me.sorihiko.dango.neoforge;

import me.sorihiko.dango.modern.DanceRender;
import me.sorihiko.dango.modern.ModernDango;
import me.sorihiko.dango.music.Dance;
import me.sorihiko.dango.modern.ModernKeyBindings;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge 入口：启动 mod，把 NeoForge 的事件转成 mod 自己的事件。
 * 只在客户端加载（dist = CLIENT），其它代码（画图、按键、界面）和 Fabric / Forge 版共用。
 */
@Mod(value = "dangomusic", dist = Dist.CLIENT)
public class DangoMusicNeoForge {

    public DangoMusicNeoForge(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent event) -> ModernKeyBindings.register(event::register));
        ModernDango.start();
        PartyNetwork.register(modBus);
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForge::onClientTick);
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForge::onPlaySound);
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForge::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForge::onMenuDraw);
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForge::onLayerDrawn);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, DangoMusicNeoForge::onRenderLivingPre);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, DangoMusicNeoForge::onRenderLivingPost);
    }

    private static void onMenuDraw(net.neoforged.neoforge.client.event.ScreenEvent.Render.Post event) {
        ModernDango.mainMenuRender(event.getGuiGraphics());
    }

    /** 原版画完暗角等镜头遮罩：Song card、灵动岛、Now playing 画在这里（暗角压不到，快捷栏照常盖在上面） */
    private static void onLayerDrawn(RenderGuiLayerEvent.Post event) {
        if (event.getName().equals(VanillaGuiLayers.CAMERA_OVERLAYS)) {
            ModernDango.hudOverlay(event.getGuiGraphics());
        }
    }

    /** 原版音乐让路：放歌时拦掉原版要开始放的背景音乐（音乐盒是 RECORDS，不受影响） */
    private static void onPlaySound(net.neoforged.neoforge.client.event.sound.PlaySoundEvent event) {
        if (me.sorihiko.dango.platform.VanillaMusicHold.active && event.getSound() != null
                && event.getSound().getSource() == net.minecraft.sounds.SoundSource.MUSIC) event.setSound(null);
    }

    /** 每个游戏 tick 开头 */
    private static void onClientTick(ClientTickEvent.Pre event) {
        ModernDango.tick();
    }

    /** 世界画完（手和 HUD 之前） */
    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            ModernDango.worldRenderEnd();
        }
    }

    // ---------------------------------------------------------------- 生物跟着节奏跳

    /** 这只生物画之前套上了跳舞的姿势（Post 里撤掉），以及给它的身体 / 头多转了多少度 */
    private static LivingEntity dancing;
    private static float danceSpin;

    /**
     * 画生物之前：压扁 / 拉长套在矩阵上（原点在脚下）；转圈改的是身体和头的朝向，
     * 不直接转矩阵，免得头顶的名字跟着转。被别的 mod 取消的就不管（最低优先级，最后一个收到）。
     */
    private static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        float[] pose = Dance.INSTANCE.pose();
        if (pose == null) return;
        LivingEntity e = event.getEntity();
        danceSpin = pose[0];
        e.yBodyRot += danceSpin;
        e.yBodyRotO += danceSpin;
        e.yHeadRot += danceSpin;
        e.yHeadRotO += danceSpin;
        event.getPoseStack().pushPose();
        DanceRender.squash(event.getPoseStack(), pose[1]);
        dancing = e;
    }

    private static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity e = dancing;
        if (e == null || e != event.getEntity()) return;
        dancing = null;
        e.yBodyRot -= danceSpin;
        e.yBodyRotO -= danceSpin;
        e.yHeadRot -= danceSpin;
        e.yHeadRotO -= danceSpin;
        event.getPoseStack().popPose();
    }
}
