package me.sorihiko.dango.forge;

import me.sorihiko.dango.legacy.LegacyDango;
import me.sorihiko.dango.legacy.LegacyKeyBindings;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

// 版本号不写在这里：Forge 会从 jar 里的 version.properties（dangomusic.version）读，
// 那个文件在构建时由根目录的 version.properties 生成
@Mod(modid = "dangomusic", name = "Dango Music",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.12.2]")
public class DangoMusicMod {

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LegacyKeyBindings.register();
        LegacyDango.start();
        ForgeBridge bridge = new ForgeBridge();
        MinecraftForge.EVENT_BUS.register(bridge);
        // 老版本 Forge 里 Tick 事件走 FML 自己的总线
        if (FMLCommonHandler.instance().bus() != MinecraftForge.EVENT_BUS) {
            FMLCommonHandler.instance().bus().register(bridge);
        }
    }
}


