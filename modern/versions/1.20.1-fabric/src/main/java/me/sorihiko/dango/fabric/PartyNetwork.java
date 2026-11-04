package me.sorihiko.dango.fabric;

import me.sorihiko.dango.party.PartyNet;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 一起听歌经当前服务器同步的网络包（局域网 / 内网穿透联机时，服务器就是房主的游戏）。
 * 只负责把一段段文字收发出去，拆包、拼包和房间数据都在 {@link PartyNet}。
 * 通道是可选的：没装这个 mod 的玩家照样能进服务器，只是不会走这条路。
 */
final class PartyNetwork {
    private static final ResourceLocation ID = new ResourceLocation("dangomusic", "party");

    private PartyNetwork() {}

    private static FriendlyByteBuf buf(String frame) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(frame, 32767);
        return buf;
    }

    /** 只注册一次：同一个通道注册两次，新版本的加载器会直接让游戏启动失败 */
    private static boolean registered;

    static void register() {
        if (registered) return;
        registered = true;
        ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, sender) ->
            PartyNet.serverReceived(player.getUUID(), buf.readUtf(32767)));
        ClientPlayNetworking.registerGlobalReceiver(ID, (client, handler, buf, sender) ->
            PartyNet.clientReceived(buf.readUtf(32767)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PartyNet.playerLeft(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PartyNet.serverStopped());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PartyNet.clientDisconnected());
        PartyNet.INSTANCE.setLink(new PartyNet.Link() {
            @Override
            public boolean toServer(String frame) {
                if (!ClientPlayNetworking.canSend(ID)) return false;
                ClientPlayNetworking.send(ID, buf(frame));
                return true;
            }

            @Override
            public void toPlayer(UUID player, String frame) {
                MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
                if (p != null && ServerPlayNetworking.canSend(p, ID)) ServerPlayNetworking.send(p, ID, buf(frame));
            }
        });
    }
}
