package me.sorihiko.dango.fabric;

import me.sorihiko.dango.party.PartyNet;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 一起听歌经当前服务器同步的网络包（局域网 / 内网穿透联机时，服务器就是房主的游戏）。
 * 只负责把一段段文字收发出去，拆包、拼包和房间数据都在 {@link PartyNet}。
 * 通道是可选的：没装这个 mod 的玩家照样能进服务器，只是不会走这条路。
 */
final class PartyNetwork {
    private PartyNetwork() {}

    /** 一个包：一段文字 */
    record Frame(String data) implements CustomPacketPayload {
        static final Type<Frame> TYPE = new Type<>(Identifier.fromNamespaceAndPath("dangomusic", "party"));
        static final StreamCodec<RegistryFriendlyByteBuf, Frame> CODEC = StreamCodec.of(
            (buf, frame) -> buf.writeUtf(frame.data(), 32767), buf -> new Frame(buf.readUtf(32767)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 只注册一次：同一个通道注册两次，新版本的加载器会直接让游戏启动失败 */
    private static boolean registered;

    static void register() {
        if (registered) return;
        registered = true;
        PayloadTypeRegistry.playC2S().register(Frame.TYPE, Frame.CODEC);
        PayloadTypeRegistry.playS2C().register(Frame.TYPE, Frame.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Frame.TYPE, (frame, context) ->
            PartyNet.serverReceived(context.player().getUUID(), frame.data()));
        ClientPlayNetworking.registerGlobalReceiver(Frame.TYPE, (frame, context) -> PartyNet.clientReceived(frame.data()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PartyNet.playerLeft(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PartyNet.serverStopped());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PartyNet.clientDisconnected());
        PartyNet.INSTANCE.setLink(new PartyNet.Link() {
            @Override
            public boolean toServer(String frame) {
                if (!ClientPlayNetworking.canSend(Frame.TYPE)) return false;
                ClientPlayNetworking.send(new Frame(frame));
                return true;
            }

            @Override
            public void toPlayer(UUID player, String frame) {
                MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
                if (p != null && ServerPlayNetworking.canSend(p, Frame.TYPE)) ServerPlayNetworking.send(p, new Frame(frame));
            }
        });
    }
}
