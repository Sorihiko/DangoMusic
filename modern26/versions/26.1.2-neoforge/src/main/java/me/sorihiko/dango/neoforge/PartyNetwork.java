package me.sorihiko.dango.neoforge;

import me.sorihiko.dango.party.PartyNet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

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

    static void register(IEventBus modBus) {
        if (registered) return;
        registered = true;
        modBus.addListener(PartyNetwork::onRegister);
        modBus.addListener(PartyNetwork::onRegisterClient);
        NeoForge.EVENT_BUS.addListener(PartyNetwork::onLoggedOut);
        NeoForge.EVENT_BUS.addListener(PartyNetwork::onServerStopped);
        NeoForge.EVENT_BUS.addListener(PartyNetwork::onClientLoggingOut);
        PartyNet.INSTANCE.setLink(new PartyNet.Link() {
            @Override
            public boolean toServer(String frame) {
                ClientPacketListener connection = Minecraft.getInstance().getConnection();
                if (connection == null || !connection.hasChannel(Frame.TYPE)) return false;
                net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new Frame(frame));
                return true;
            }

            @Override
            public void toPlayer(UUID player, String frame) {
                MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
                if (p != null && p.connection.hasChannel(Frame.TYPE)) PacketDistributor.sendToPlayer(p, new Frame(frame));
            }
        });
    }

    private static void onRegister(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playBidirectional(Frame.TYPE, Frame.CODEC, PartyNetwork::onFrame);
    }

    /** 这个版本的 NeoForge：服务器发来的包要在客户端单独登记处理方法，不然游戏启动时直接报错 */
    private static void onRegisterClient(RegisterClientPayloadHandlersEvent event) {
        event.register(Frame.TYPE, (frame, context) -> PartyNet.clientReceived(frame.data()));
    }

    private static void onFrame(Frame frame, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) PartyNet.serverReceived(player.getUUID(), frame.data());
        else PartyNet.clientReceived(frame.data());
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PartyNet.playerLeft(event.getEntity().getUUID());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        PartyNet.serverStopped();
    }

    private static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PartyNet.clientDisconnected();
    }
}
