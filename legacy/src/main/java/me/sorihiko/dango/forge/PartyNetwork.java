package me.sorihiko.dango.forge;

import io.netty.buffer.Unpooled;
import me.sorihiko.dango.party.PartyNet;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.PacketBuffer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

import java.util.UUID;

/**
 * 一起听歌经当前服务器同步的网络包（局域网 / 内网穿透联机时，服务器就是房主的游戏）。
 * 只负责把一段段文字收发出去，拆包、拼包和房间数据都在 {@link PartyNet}。
 * 通道是可选的：没装这个 mod 的玩家照样能进服务器，只是不会走这条路。
 */
final class PartyNetwork {
    private static FMLEventChannel channel;

    private PartyNetwork() {}

    private static FMLProxyPacket packet(String frame) {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeString(frame);
        return new FMLProxyPacket(buf, PartyNet.CHANNEL);
    }

    private static String read(FMLProxyPacket packet) {
        return new PacketBuffer(packet.payload()).readStringFromBuffer(32767);
    }

    /** 只注册一次：同一个通道注册两次，新版本的加载器会直接让游戏启动失败 */
    private static boolean registered;

    static void register() {
        if (registered) return;
        registered = true;
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(PartyNet.CHANNEL);
        PartyNetwork handler = new PartyNetwork();
        channel.register(handler);
        FMLCommonHandler.instance().bus().register(handler);
        PartyNet.INSTANCE.setLink(new PartyNet.Link() {
            @Override
            public boolean toServer(String frame) {
                if (Minecraft.getMinecraft().getNetHandler() == null) return false;
                channel.sendToServer(packet(frame));
                return true;
            }

            @Override
            public void toPlayer(UUID player, String frame) {
                MinecraftServer server = MinecraftServer.getServer();
                EntityPlayerMP p = server == null ? null : server.getConfigurationManager().getPlayerByUUID(player);
                if (p != null) channel.sendTo(packet(frame), p);
            }
        });
    }

    @SubscribeEvent
    public void onServerPacket(FMLNetworkEvent.ServerCustomPacketEvent event) {
        EntityPlayerMP player = ((NetHandlerPlayServer) event.handler).playerEntity;
        PartyNet.serverReceived(player.getUniqueID(), read(event.packet));
    }

    @SubscribeEvent
    public void onClientPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
        PartyNet.clientReceived(read(event.packet));
    }

    @SubscribeEvent
    public void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        PartyNet.clientDisconnected();
    }

    @SubscribeEvent
    public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PartyNet.playerLeft(event.player.getUniqueID());
    }
}
