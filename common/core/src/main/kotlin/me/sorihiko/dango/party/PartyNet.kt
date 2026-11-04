package me.sorihiko.dango.party

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 一起听歌经当前服务器（局域网 / 内网穿透联机时就是房主的游戏）同步：
 * 各加载器只负责收发一段段文字（[Link]），这里把消息拆成小块、拼回来，交给 [PartyServer] / [ServerTransport]。
 *
 * 客户端发给服务器的包最多 32 KB，所以一条消息拆成每块最多 [CHUNK] 个字（中文 3 字节一个也不会超）。
 * 一块的格式：msgId|第几块|共几块|内容
 */
object PartyNet {

    /** 网络包通道名（各加载器注册用） */
    const val CHANNEL = "dangomusic:party"

    /** 协议版本：两边不一样时客户端不用服务器同步，退回 Firebase */
    const val PROTOCOL = 1

    private const val CHUNK = 8000

    /** 加载器实现：真正把一块发出去 */
    interface Link {
        /** 客户端 → 当前服务器；没连着服务器或服务器没有这个通道时返回 false */
        fun toServer(frame: String): Boolean

        /** 服务器 → 某个玩家 */
        fun toPlayer(player: UUID, frame: String)
    }

    @Volatile var link: Link? = null

    private val ids = AtomicLong()

    private fun frames(message: String): List<String> {
        val id = ids.incrementAndGet()
        val count = maxOf(1, (message.length + CHUNK - 1) / CHUNK)
        return (0 until count).map { i -> "$id|$i|$count|" + message.substring(i * CHUNK, minOf(message.length, (i + 1) * CHUNK)) }
    }

    /** 拼消息：按"谁发的 + msgId"放，凑齐了返回整条 */
    private class Assembler {
        private class Partial(val parts: Array<String?>, var got: Int = 0, val at: Long = System.currentTimeMillis())
        private val partial = ConcurrentHashMap<String, Partial>()

        fun accept(sender: String, frame: String): String? {
            val p1 = frame.indexOf('|')
            val p2 = frame.indexOf('|', p1 + 1)
            val p3 = frame.indexOf('|', p2 + 1)
            if (p1 <= 0 || p2 <= p1 || p3 <= p2) return null
            val id = frame.substring(0, p1)
            val index = frame.substring(p1 + 1, p2).toIntOrNull() ?: return null
            val count = frame.substring(p2 + 1, p3).toIntOrNull() ?: return null
            if (count !in 1..256 || index !in 0 until count) return null
            val body = frame.substring(p3 + 1)
            if (count == 1) return body
            val key = "$sender|$id"
            val now = System.currentTimeMillis()
            // 很久没凑齐的（对方中途掉线）丢掉
            partial.entries.removeIf { now - it.value.at > 30_000L }
            val p = partial.computeIfAbsent(key) { Partial(arrayOfNulls(count)) }
            synchronized(p) {
                if (p.parts.size != count || p.parts[index] != null) return null
                p.parts[index] = body
                p.got++
                if (p.got < count) return null
            }
            partial.remove(key)
            return p.parts.joinToString("")
        }

        fun forget(sender: String) {
            partial.keys.removeIf { it.startsWith("$sender|") }
        }

        fun clear() = partial.clear()
    }

    private val fromServer = Assembler()
    private val fromPlayers = Assembler()

    private fun parse(text: String): JsonObject? = try {
        JsonParser().parse(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- 客户端

    /** 发一条消息给当前服务器 */
    internal fun sendToServer(message: JsonObject): Boolean {
        val l = link ?: return false
        for (f in frames(message.toString())) if (!l.toServer(f)) return false
        return true
    }

    /** 加载器：客户端收到服务器发来的一块 */
    @JvmStatic
    fun clientReceived(frame: String) {
        val text = fromServer.accept("server", frame) ?: return
        parse(text)?.let(ServerTransport::onMessage)
    }

    /** 加载器：客户端离开了世界 / 服务器 */
    @JvmStatic
    fun clientDisconnected() {
        fromServer.clear()
        ServerTransport.disconnected()
    }

    // ---------------------------------------------------------------- 服务器（房主的游戏）

    internal fun sendToPlayer(player: UUID, message: JsonObject) {
        val l = link ?: return
        try {
            for (f in frames(message.toString())) l.toPlayer(player, f)
        } catch (e: Exception) {
            DangoLogger.warn("Listen Together: send to player failed: {}", e.toString())
        }
    }

    /** 加载器：服务器收到某个玩家发来的一块 */
    @JvmStatic
    fun serverReceived(player: UUID, frame: String) {
        val text = fromPlayers.accept(player.toString(), frame) ?: return
        parse(text)?.let { PartyServer.handle(player, it) }
    }

    /** 加载器：玩家离开了服务器 */
    @JvmStatic
    fun playerLeft(player: UUID) {
        fromPlayers.forget(player.toString())
        PartyServer.playerLeft(player)
    }

    /** 加载器：服务器关了（退出存档） */
    @JvmStatic
    fun serverStopped() {
        fromPlayers.clear()
        PartyServer.clear()
    }
}
