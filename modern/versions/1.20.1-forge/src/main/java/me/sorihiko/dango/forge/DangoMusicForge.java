package me.sorihiko.dango.forge;

import me.sorihiko.dango.modern.DanceRender;
import me.sorihiko.dango.modern.ModernDango;
import me.sorihiko.dango.music.Dance;
import me.sorihiko.dango.modern.ModernKeyBindings;
import me.sorihiko.dango.party.ListenParty;
import me.sorihiko.dango.platform.PartyLinks;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge 入口：启动 mod，把 Forge 的事件转成 mod 自己的事件。
 * 其它代码（画图、按键、界面）和 Fabric 版共用。
 */
@Mod("dangomusic")
public class DangoMusicForge {

    public DangoMusicForge() {
        // 一起听歌的服务器通道：客户端和专用服务器都要注册（通道本身是可选的，原版客户端照常进服）
        PartyNetwork.register();
        // 客户端功能只在客户端初始化；专用服务器只跑一起听歌的房间数据
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Client.init();
        }
    }

    /** 单独放一个类：服务器上不会加载到这些客户端类 */
    private static final class Client {
        static void init() {
            // 把「一起听走服务器」的通道交给共用代码；不设置的话仍然走 Firebase
            PartyLinks.INSTANCE.setLink(ForgePartyLink.INSTANCE);
            PartyNetwork.setClientSink(ForgePartyLink.INSTANCE);
            FMLJavaModLoadingContext.get().getModEventBus().addListener(Client::onRegisterKeys);
            ModernDango.start();
            MinecraftForge.EVENT_BUS.addListener(Client::onClientTick);
            MinecraftForge.EVENT_BUS.addListener(Client::onRenderLevel);
            MinecraftForge.EVENT_BUS.addListener(Client::onMenuDraw);
            MinecraftForge.EVENT_BUS.addListener(Client::onOverlayDrawn);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, Client::onRenderLivingPre);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, Client::onRenderLivingPost);
            MinecraftForge.EVENT_BUS.addListener(Client::onLoggingIn);
            MinecraftForge.EVENT_BUS.addListener(Client::onLoggingOut);
        }

        /** 连上服务器：拿到自己的 UUID（房间里的身份） */
        private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
            // 这里不能 reset：服务器的 InfoMsg 可能已经先到了，清空会让界面误判成「服务器不支持一起听」。
            // 断线时 onLoggingOut 已经 reset 过了，换服务器也一定会先断开。
            try {
                ForgePartyLink.INSTANCE.setPlayerId(event.getPlayer().getStringUUID());
            } catch (Throwable ignored) {
                // 单人存档刚开始时拿不到也无所谓，收到服务器信息后会再试
            }
            ListenParty.INSTANCE.onSessionStarted();
        }

        /** 退服 / 退存档：服务端已经把我们移出房间，这里退出房间并停止播放 */
        private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            try {
                ListenParty.INSTANCE.onSessionEnded();
            } catch (Throwable t) {
                org.apache.logging.log4j.LogManager.getLogger("DangoMusic")
                        .warn("Listen Together: leaving session failed: {}", t.toString());
            }
            ForgePartyLink.INSTANCE.reset();
        }

        private static void onMenuDraw(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
            ModernDango.mainMenuRender(event.getGuiGraphics());
        }
        static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            ModernKeyBindings.register(event::register);
        }

        /** 每个游戏 tick 开头 */
        static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.START) ModernDango.tick();
        }

        /** 原版画完暗角：Song card、灵动岛、Now playing 画在这里（暗角压不到，快捷栏照常盖在上面） */
        static void onOverlayDrawn(RenderGuiOverlayEvent.Post event) {
            if (event.getOverlay().id().equals(VanillaGuiOverlay.VIGNETTE.id())) {
                ModernDango.hudOverlay(event.getGuiGraphics());
            }
        }

        /** LevelRenderer 返回后（光影合成之后、手和 HUD 之前），对应 Fabric 的 GameRenderer 注入点 */
        static void onRenderLevel(RenderLevelStageEvent event) {
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
}
