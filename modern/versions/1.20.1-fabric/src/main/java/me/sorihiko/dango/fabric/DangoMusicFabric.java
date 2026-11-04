package me.sorihiko.dango.fabric;

import me.sorihiko.dango.modern.ModernDango;
import me.sorihiko.dango.modern.ModernKeyBindings;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Fabric 入口：启动 mod，把 Fabric 的事件转成 mod 自己的事件 */
public class DangoMusicFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModernKeyBindings.register(KeyBindingHelper::registerKeyBinding);
        ModernDango.start();
        PartyNetwork.register();
        ClientTickEvents.START_CLIENT_TICK.register(client -> ModernDango.tick());
    }
}
