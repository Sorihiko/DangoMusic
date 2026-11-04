package me.sorihiko.dango.fabric;

import me.sorihiko.dango.modern.ModernDango;
import me.sorihiko.dango.modern.ModernKeyBindings;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Fabric 入口：启动 mod，把 Fabric 的事件转成 mod 自己的事件。
 * 画世界 / 画界面的时机用 {@link me.sorihiko.dango.fabric.mixin.GameRendererMixin} 挂在原版里。
 */
public class DangoMusicFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModernKeyBindings.register(KeyMappingHelper::registerKeyMapping);
        ModernDango.start();
        PartyNetwork.register();
        ClientTickEvents.START_CLIENT_TICK.register(client -> ModernDango.tick());
    }
}
