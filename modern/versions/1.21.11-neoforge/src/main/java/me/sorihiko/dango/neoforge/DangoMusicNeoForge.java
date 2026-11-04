package me.sorihiko.dango.neoforge;

import me.sorihiko.dango.modern.DanceRender;
import me.sorihiko.dango.modern.ModernDango;
import me.sorihiko.dango.music.Dance;
import me.sorihiko.dango.modern.ModernKeyBindings;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
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
 * 只在客户端加载（dist = CLIENT），其它代码（画图、按键、界面）和 Fabric 版共用。
 */
@Mod(value = "dangomusic", dist = Dist.CLIENT)
public class DangoMusicNeoForge {

    public DangoMusicNeoForge(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent event) -> ModernKeyBindings.register(event::register));
        PartyNetwork.register(modBus);
        // 这个版本的 NeoForge 加载 mod 时游戏本体（Minecraft）还没创建，
        // 读配置文件夹等都要用到它，所以等第一个游戏 tick 再启动
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForge::onClientTick);
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

    /** 每个游戏 tick 开头 */
    private static void onClientTick(ClientTickEvent.Pre event) {
        ModernDango.start();   // 只有第一次会真正启动
        ModernDango.tick();
    }

    /** 整个世界画完（含天气、云），画手和 HUD 之前 */
    private static void onRenderLevel(RenderLevelStageEvent.AfterLevel event) {
        ModernDango.worldRenderEnd();
    }

    // ---------------------------------------------------------------- 生物跟着节奏跳

    /** 这只生物画之前套上了跳舞的姿势，Post 里撤掉 */
    private static Object dancing;

    /**
     * 画生物之前：压扁 / 拉长套在矩阵上（原点在脚下）；转圈改的是这一帧的身体朝向（每帧重新收集，不用改回来），
     * 不直接转矩阵，免得头顶的名字跟着转。被别的 mod 取消的就不管（最低优先级，最后一个收到）。
     */
    private static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?, ?> event) {
        float[] pose = Dance.INSTANCE.pose();
        if (pose == null) return;
        event.getRenderState().bodyRot += pose[0];
        event.getPoseStack().pushPose();
        DanceRender.squash(event.getPoseStack(), pose[1]);
        dancing = event.getRenderState();
    }

    private static void onRenderLivingPost(RenderLivingEvent.Post<?, ?, ?> event) {
        if (dancing == null || dancing != event.getRenderState()) return;
        dancing = null;
        event.getPoseStack().popPose();
    }
}
