package me.sorihiko.dango.fabric;

import me.sorihiko.dango.modern.ModernDango;
import me.sorihiko.dango.modern.ModernKeyBindings;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Fabric 入口：启动 mod，把 Fabric 的事件转成 mod 自己的事件。
 * "世界画完"在这个版本的 Fabric API 里没有合适的事件（最后一个在天气和云之前），
 * 改用 {@link me.sorihiko.dango.fabric.mixin.GameRendererMixin} 挂在原版画完世界的地方。
 */
public class DangoMusicFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModernKeyBindings.register(KeyBindingHelper::registerKeyBinding);
        ModernDango.start();
        PartyNetwork.register();
        ClientTickEvents.START_CLIENT_TICK.register(client -> ModernDango.tick());
    }
}
