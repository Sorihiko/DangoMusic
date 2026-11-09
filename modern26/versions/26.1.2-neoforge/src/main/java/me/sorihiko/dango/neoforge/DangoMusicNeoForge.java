package me.sorihiko.dango.neoforge;

import me.sorihiko.dango.modern.ModernDango;
import me.sorihiko.dango.modern.ModernKeyBindings;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
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
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForge::onPlaySound);
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForge::onRenderLevel);
    }

    /** 原版音乐让路：放歌时拦掉原版要开始放的背景音乐（音乐盒是 RECORDS，不受影响） */
    private static void onPlaySound(net.neoforged.neoforge.client.event.sound.PlaySoundEvent event) {
        if (me.sorihiko.dango.platform.VanillaMusicHold.active && event.getSound() != null
                && event.getSound().getSource() == net.minecraft.sounds.SoundSource.MUSIC) event.setSound(null);
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
}
