package me.sorihiko.dango.forge;

import me.sorihiko.dango.platform.PartyLink;

import net.minecraft.client.Minecraft;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端侧的 [PartyLink]（1.20.1 Forge）。
 *
 * <p>状态都由 {@link DangoMusicForge} 里的客户端事件维护：
 * 登录时拿到玩家 UUID、收到服务器的 {@link PartyNetwork.InfoMsg} 后 [available] 变 true；
 * 断线时清空，等待下一个服务器重新握手。
 *
 * <p>发包必须回主线程（SimpleChannel 的编码不是线程安全的），等待回包仍然发生在
 * 一起听自己的工作线程上，所以游戏主线程不会被卡住。
 */
public final class ForgePartyLink implements PartyLink, PartyNetwork.ClientSink {

    private static final Logger LOG = LogManager.getLogger("DangoMusic");

    public static final ForgePartyLink INSTANCE = new ForgePartyLink();

    private volatile boolean available;
    private volatile int protocol;
    private volatile String name = "";
    private volatile String playerId = "";
    private volatile Handler handler;

    private ForgePartyLink() {
    }

    // ---------------------------------------------------------------- 生命周期

    /** 收到服务器的信息：从这一刻起才能用服务器通道 */
    public void info(int protocol, String name) {
        this.protocol = protocol;
        this.name = name == null ? "" : name;
        boolean was = this.available;
        this.available = true;
        if (!was) {
            LOG.info("Listen Together: using this server (protocol {})", protocol);
        }
    }

    public void setPlayerId(String id) {
        this.playerId = id == null ? "" : id;
    }

    /** 断线 / 换服务器 */
    public void reset() {
        available = false;
        protocol = 0;
        name = "";
        playerId = "";
    }

    // ---------------------------------------------------------------- PartyLink

    @Override
    public boolean getAvailable() {
        return available;
    }

    @Override
    public int getProtocol() {
        return protocol;
    }

    @Override
    public String getServerName() {
        return name;
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    @Override
    public boolean send(int op, long id, String path, String etag, String body) {
        if (!available) {
            return false;
        }
        PartyNetwork.RequestMsg message = new PartyNetwork.RequestMsg(op, id, path,
                etag == null ? "" : etag, body == null ? "" : body);
        Minecraft mc = Minecraft.getInstance();
        Runnable task = () -> {
            try {
                PartyNetwork.CHANNEL.sendToServer(message);
            } catch (Throwable t) {
                // 断线的一瞬间发包会抛异常，不能让游戏崩
                LOG.warn("Listen Together: send failed: {}", t.toString());
            }
        };
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
        return true;
    }

    @Override
    public void bind(Handler handler) {
        this.handler = handler;
    }

    // ---------------------------------------------------------------- 收包（网络线程）

    @Override
    public void response(long id, int status, long serverTime, String etag, String body) {
        Handler h = handler;
        if (h != null) {
            h.onResponse(id, status, serverTime, etag == null ? "" : etag, body == null ? "" : body);
        }
    }

    @Override
    public void event(long subId, String path, String data) {
        Handler h = handler;
        if (h != null) {
            h.onEvent(subId, path, data == null ? "null" : data);
        }
    }
}
