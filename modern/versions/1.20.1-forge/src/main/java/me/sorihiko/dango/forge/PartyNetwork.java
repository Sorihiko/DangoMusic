package me.sorihiko.dango.forge;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import me.sorihiko.dango.party.PartyProtocol;
import me.sorihiko.dango.party.server.PartyStore;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 一起听歌的服务器通道（1.20.1 Forge）。
 *
 * <p>客户端把「本来要发给 Firebase 的请求」打包发到这里，[PartyStore] 用它自己的 JSON 树处理，
 * 再把变化推给订阅了这个房间的人。客户端不需要 Firebase、不需要代理，也不需要开端口。
 *
 * <p>线程：
 * <ul>
 *   <li>请求（C2S）在服务端主线程处理（要访问玩家、发包）；</li>
 *   <li>响应和推送（S2C）在网络线程处理 —— 只唤醒等待的 future / 交给上层的推送回调，
 *       不碰游戏对象。以前 DangoShare 的教训是：把回包排到主线程会让请求在主线程卡顿的时候超时。</li>
 * </ul>
 */
public final class PartyNetwork {

    private static final Logger LOG = LogManager.getLogger("DangoMusic");

    /** 通道是可选的：没装这个 mod 的客户端（原版）照样能进服务器 */
    private static final String PROTOCOL = String.valueOf(PartyProtocol.VERSION);

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("dangomusic", "party"),
            () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL),
            NetworkRegistry.acceptMissingOr(PROTOCOL));

    /** 客户端收包出口；专用服务器上一直是 null（不会加载到客户端类） */
    public interface ClientSink {
        void response(long id, int status, long serverTime, String etag, String body);

        void event(long subId, String path, String data);

        void info(int protocol, String name);
    }

    private static volatile ClientSink clientSink;

    private PartyNetwork() {
    }

    public static void setClientSink(ClientSink sink) {
        clientSink = sink;
    }

    /** 两种物理端都要调用：注册消息类型和服务器侧的事件 */
    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(RequestMsg.class, id++)
                .encoder(RequestMsg::encode).decoder(RequestMsg::decode)
                .consumerMainThread(PartyNetwork::onRequest).add();
        CHANNEL.messageBuilder(ResponseMsg.class, id++)
                .encoder(ResponseMsg::encode).decoder(ResponseMsg::decode)
                .consumerNetworkThread(PartyNetwork::onResponse).add();
        CHANNEL.messageBuilder(EventMsg.class, id++)
                .encoder(EventMsg::encode).decoder(EventMsg::decode)
                .consumerNetworkThread(PartyNetwork::onEvent).add();
        CHANNEL.messageBuilder(InfoMsg.class, id++)
                .encoder(InfoMsg::encode).decoder(InfoMsg::decode)
                .consumerNetworkThread(PartyNetwork::onInfo).add();

        MinecraftForge.EVENT_BUS.addListener(PartyNetwork::onPlayerLogin);
        MinecraftForge.EVENT_BUS.addListener(PartyNetwork::onPlayerLogout);
        MinecraftForge.EVENT_BUS.addListener(PartyNetwork::onServerStopped);
        LOG.info("Listen Together: server channel ready (protocol {})", PartyProtocol.VERSION);
    }

    // ---------------------------------------------------------------- 服务器：请求处理

    private static void onRequest(RequestMsg msg, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender != null) {
            try {
                handle(sender, msg);
            } catch (Throwable t) {
                LOG.error("Listen Together: failed to handle {} {}", PartyProtocol.opName(msg.op()), msg.path(), t);
            }
        }
        ctx.setPacketHandled(true);
    }

    private static void handle(ServerPlayer sender, RequestMsg msg) {
        long id = msg.id();
        int status = 200;
        JsonElement value = null;
        String etag = "";
        String changed = null;

        if (!allowed(msg.path())) {
            status = 403;
        } else if (msg.op() == PartyProtocol.OP_SUBSCRIBE) {
            String player = sender.getStringUUID();
            value = PartyStore.INSTANCE.subscribe(player, (int) id, msg.path());
            etag = PartyStore.INSTANCE.etagOf(msg.path());
            // 和 Firebase 连上时的第一帧一样：先把整个房间推过去，客户端据此建好镜像
            if (value != null && canReceive(sender)) {
                send(sender, new EventMsg(id, "/", value.toString()));
            }
        } else if (msg.op() == PartyProtocol.OP_UNSUBSCRIBE) {
            PartyStore.INSTANCE.unsubscribe(sender.getStringUUID(), (int) id);
        } else {
            JsonElement body = parse(msg.body());
            PartyStore.Result result = PartyStore.INSTANCE.handle(msg.op(), msg.path(), msg.etag(), body);
            status = result.getStatus();
            value = result.getValue();
            etag = result.getEtag();
            changed = result.getChangedPath();
        }

        String json = value == null || value.isJsonNull() ? "null" : value.toString();
        send(sender, new ResponseMsg(id, status, System.currentTimeMillis(), etag == null ? "" : etag, json));
        if (changed != null) {
            broadcast(sender.getServer(), changed);
        }
    }

    /** 只允许一起听用到的两棵子树，层数也有上限 */
    private static boolean allowed(String path) {
        String p = PartyStore.normalize(path);
        if (p.isEmpty() || p.length() > PartyProtocol.MAX_PATH) {
            return false;
        }
        if (p.split("/").length > PartyStore.MAX_DEPTH) {
            return false;
        }
        return p.equals("rooms") || p.startsWith("rooms/") || p.equals("clock") || p.startsWith("clock/");
    }

    private static JsonElement parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return JsonParser.parseString(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static void send(ServerPlayer player, Object message) {
        try {
            if (!canReceive(player)) {
                return;
            }
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        } catch (Throwable t) {
            LOG.warn("Listen Together: could not send to {}: {}", player.getGameProfile().getName(), t.toString());
        }
    }

    /**
     * 能不能给这个玩家发一起听的包。
     *
     * <p>单人 / 局域网主机用内存连接，Forge 不给它挂连接信息。
     * 照「有没有连接数据」判断会一律返回 false，主机就永远收不到握手 ——
     * 所以这种情况直接放行（两端是同一份 mod，肯定支持）。
     *
     * <p>联机服才看连接数据：原版客户端没有它，也就没有这个通道，不该发包。
     */
    private static boolean canReceive(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null && server.isSingleplayer()) {
            return true;
        }
        try {
            return NetworkHooks.getConnectionData(player.connection.connection) != null;
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------------------------------------------------------------- 服务器：广播 / 退服

    private static void broadcast(MinecraftServer server, String changedPath) {
        if (server == null) {
            return;
        }
        for (PartyStore.Sub sub : PartyStore.INSTANCE.subscribers()) {
            String relative = PartyStore.relative(sub.getPath(), changedPath);
            if (relative == null) {
                continue;
            }
            ServerPlayer player = byUuid(server, sub.getPlayerId());
            if (player == null) {
                continue;
            }
            JsonElement value = "/".equals(relative)
                    ? PartyStore.INSTANCE.get(sub.getPath())
                    : PartyStore.INSTANCE.get(changedPath);
            String data = value == null || value.isJsonNull() ? "null" : value.toString();
            send(player, new EventMsg(sub.getSubId(), relative, data));
        }
    }

    private static ServerPlayer byUuid(MinecraftServer server, String id) {
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(id));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        String name = server == null ? "" : server.getMotd();
        boolean sent = canReceive(player);
        if (sent) {
            send(player, new InfoMsg(PartyProtocol.VERSION, name));
        }
        // 只在真的发了握手包时才算「能用」；单人/局域网主机那边也会走到这里
        LOG.info("Listen Together: {} {} the server transport (protocol {}, singleplayer={})",
                player.getGameProfile().getName(),
                sent ? "can use" : "cannot use",
                PartyProtocol.VERSION,
                server != null && server.isSingleplayer());
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        UUID id = player.getUUID();
        List<String> changed = PartyStore.INSTANCE.dropPlayer(id.toString());
        for (String path : changed) {
            broadcast(server, path);
        }
        if (!changed.isEmpty()) {
            LOG.info("Listen Together: removed {} from {} room(s)", player.getGameProfile().getName(), changed.size());
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        // 房间只在内存里：关服就清空，下次开服重新建房
        PartyStore.INSTANCE.reset();
    }

    // ---------------------------------------------------------------- 客户端收包

    private static void onResponse(ResponseMsg msg, Supplier<NetworkEvent.Context> supplier) {
        ClientSink sink = clientSink;
        if (sink != null) {
            try {
                sink.response(msg.id(), msg.status(), msg.serverTime(), msg.etag(), msg.body());
            } catch (Throwable t) {
                LOG.warn("Listen Together: response handler failed: {}", t.toString());
            }
        }
        supplier.get().setPacketHandled(true);
    }

    private static void onEvent(EventMsg msg, Supplier<NetworkEvent.Context> supplier) {
        ClientSink sink = clientSink;
        if (sink != null) {
            try {
                sink.event(msg.subId(), msg.path(), msg.data());
            } catch (Throwable t) {
                LOG.warn("Listen Together: push handler failed: {}", t.toString());
            }
        }
        supplier.get().setPacketHandled(true);
    }

    private static void onInfo(InfoMsg msg, Supplier<NetworkEvent.Context> supplier) {
        ClientSink sink = clientSink;
        if (sink != null) {
            try {
                sink.info(msg.protocol(), msg.name());
            } catch (Throwable t) {
                LOG.warn("Listen Together: info handler failed: {}", t.toString());
            }
        }
        supplier.get().setPacketHandled(true);
    }

    // ---------------------------------------------------------------- 消息

    /** 客户端 → 服务器：一个数据库请求 */
    public record RequestMsg(int op, long id, String path, String etag, String body) {

        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(op);
            buf.writeVarLong(id);
            buf.writeUtf(path, PartyProtocol.MAX_PATH);
            buf.writeUtf(etag, 64);
            buf.writeUtf(body, PartyProtocol.MAX_BODY);
        }

        static RequestMsg decode(FriendlyByteBuf buf) {
            return new RequestMsg(buf.readVarInt(), buf.readVarLong(),
                    buf.readUtf(PartyProtocol.MAX_PATH), buf.readUtf(64), buf.readUtf(PartyProtocol.MAX_BODY));
        }
    }

    /** 服务器 → 客户端：请求结果（[serverTime] 用来校准两边时钟） */
    public record ResponseMsg(long id, int status, long serverTime, String etag, String body) {

        void encode(FriendlyByteBuf buf) {
            buf.writeVarLong(id);
            buf.writeVarInt(status);
            buf.writeLong(serverTime);
            buf.writeUtf(etag, 64);
            buf.writeUtf(body, PartyProtocol.MAX_BODY);
        }

        static ResponseMsg decode(FriendlyByteBuf buf) {
            return new ResponseMsg(buf.readVarLong(), buf.readVarInt(), buf.readLong(),
                    buf.readUtf(64), buf.readUtf(PartyProtocol.MAX_BODY));
        }
    }

    /** 服务器 → 客户端：订阅推送（[path] 相对订阅路径，"/" 表示整个房间） */
    public record EventMsg(long subId, String path, String data) {

        void encode(FriendlyByteBuf buf) {
            buf.writeVarLong(subId);
            buf.writeUtf(path, PartyProtocol.MAX_PATH);
            buf.writeUtf(data, PartyProtocol.MAX_BODY);
        }

        static EventMsg decode(FriendlyByteBuf buf) {
            return new EventMsg(buf.readVarLong(), buf.readUtf(PartyProtocol.MAX_PATH),
                    buf.readUtf(PartyProtocol.MAX_BODY));
        }
    }

    /** 服务器 → 客户端：登录后告诉客户端「服务器装了这个 mod，协议号是多少」 */
    public record InfoMsg(int protocol, String name) {

        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(protocol);
            buf.writeUtf(name, 64);
        }

        static InfoMsg decode(FriendlyByteBuf buf) {
            return new InfoMsg(buf.readVarInt(), buf.readUtf(64));
        }
    }
}
