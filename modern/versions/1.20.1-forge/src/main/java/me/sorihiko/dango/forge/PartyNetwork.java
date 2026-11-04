package me.sorihiko.dango.forge;

import me.sorihiko.dango.party.PartyNet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 一起听歌经当前服务器同步的网络包（局域网 / 内网穿透联机时，服务器就是房主的游戏）。
 * 只负责把一段段文字收发出去，拆包、拼包和房间数据都在 {@link PartyNet}。
 * 通道是可选的：没装这个 mod 的玩家照样能进服务器，只是不会走这条路。
 */
final class PartyNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("dangomusic", "party"), () -> VERSION,
        NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));

    private PartyNetwork() {}

    /** 一个包：一段文字 */
    record Frame(String data) {
        static void encode(Frame frame, FriendlyByteBuf buf) {
            buf.writeUtf(frame.data(), 32767);
        }

        static Frame decode(FriendlyByteBuf buf) {
            return new Frame(buf.readUtf(32767));
        }

        static void handle(Frame frame, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            ServerPlayer sender = context.getSender();
            if (sender != null) PartyNet.serverReceived(sender.getUUID(), frame.data());
            else PartyNet.clientReceived(frame.data());
            context.setPacketHandled(true);
        }
    }

    /** 只注册一次：同一个通道注册两次，新版本的加载器会直接让游戏启动失败 */
    private static boolean registered;

    static void register() {
        if (registered) return;
        registered = true;
        CHANNEL.registerMessage(0, Frame.class, Frame::encode, Frame::decode, Frame::handle);
        MinecraftForge.EVENT_BUS.addListener(PartyNetwork::onLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(PartyNetwork::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(PartyNetwork::onClientLoggingOut);
        PartyNet.INSTANCE.setLink(new PartyNet.Link() {
            @Override
            public boolean toServer(String frame) {
                ClientPacketListener connection = Minecraft.getInstance().getConnection();
                if (connection == null || !CHANNEL.isRemotePresent(connection.getConnection())) return false;
                CHANNEL.sendToServer(new Frame(frame));
                return true;
            }

            @Override
            public void toPlayer(UUID player, String frame) {
                MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
                if (p != null && CHANNEL.isRemotePresent(p.connection.connection)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Frame(frame));
            }
        });
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
