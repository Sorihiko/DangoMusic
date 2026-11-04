package me.sorihiko.dango.party

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import me.sorihiko.dango.DangoLogger
import java.security.MessageDigest
import java.util.UUID

/**
 * 服务器这边（局域网 / 内网穿透联机时是房主的游戏）的一起听数据：和 Firebase 实时数据库一样的一棵 JSON 树，
 * 只放在内存里，退出存档就没了（和 Firebase 上的临时房间一样）。
 *
 * 收到的每条消息：{"id":序号,"op":操作,"path":"rooms/...",...}，回一条 {"re":序号,"status":200,...}；
 * 有人监听的路径变了，推一条 {"ev":监听号,"data":整个路径现在的数据}。
 * 谁能写什么和 database.rules.json 一样：房间只有房主能改（没人的房间可以接管），成员只能改自己那条，
 * 请求只有成员、并且房主开着"允许成员控制"时能写。
 */
internal object PartyServer {

    /** 一个房间最多多大（防止有人往里塞特别大的东西） */
    private const val MAX_ROOM_CHARS = 1_000_000
    private const val ABANDONED_MS = 600_000L

    private val lock = Any()
    private var root = JsonObject()

    private class Listener(val player: UUID, val sub: Int, val path: List<String>)
    private val listeners = ArrayList<Listener>()

    fun clear() = synchronized(lock) {
        root = JsonObject()
        listeners.clear()
    }

    // ---------------------------------------------------------------- 树的读写

    private fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    private fun read(path: List<String>): JsonElement? {
        var node: JsonElement = root
        for (key in path) node = (node as? JsonObject)?.get(key) ?: return null
        return node
    }

    /** 把 [path] 处换成 [value]（null 删掉），删空的对象也一起删掉，和数据库一样 */
    private fun write(path: List<String>, value: JsonElement?) {
        root = (set(root, path, value) as? JsonObject) ?: JsonObject()
    }

    private fun set(node: JsonElement?, path: List<String>, value: JsonElement?): JsonElement? {
        if (path.isEmpty()) return value
        val obj = node as? JsonObject ?: JsonObject()
        val key = path[0]
        val child = set(obj.get(key), path.subList(1, path.size), value)
        if (child == null) obj.remove(key) else obj.add(key, child)
        return if (obj.entrySet().isEmpty()) null else obj
    }

    /** 写入前把 {".sv":"timestamp"} 换成服务器现在的时间；空对象 / null 当成删除 */
    private fun resolve(value: JsonElement?, now: Long): JsonElement? {
        if (value == null || value.isJsonNull) return null
        if (!value.isJsonObject) return value
        val o = value.asJsonObject
        if (o.entrySet().size == 1 && o.get(".sv")?.takeIf { it.isJsonPrimitive }?.asString == "timestamp") return JsonPrimitive(now)
        val out = JsonObject()
        for ((k, v) in o.entrySet()) resolve(v, now)?.let { out.add(k, it) }
        return if (out.entrySet().isEmpty()) null else out
    }

    private fun etag(value: JsonElement?): String {
        val text = value?.toString() ?: "null"
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    // ---------------------------------------------------------------- 谁能写

    /** 和 database.rules.json 一样的判断。[path] 是要写的位置 */
    private fun canWrite(uid: String, path: List<String>, now: Long): Boolean {
        if (path.size < 2 || path[0] != "rooms") return false
        val code = path[1]
        if (code.length > 16) return false
        val room = read(listOf("rooms", code)) as? JsonObject
        val host = room?.get("host")?.takeIf { it.isJsonPrimitive }?.asString
        val hostSeen = ((room?.get("members") as? JsonObject)?.get(host ?: "") as? JsonObject)
            ?.get("lastSeen")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        // 房间本身：还没有、自己是房主，或者房主很久没心跳了（可以接管）
        if (room == null || host == uid || now - hostSeen > ABANDONED_MS) return true
        // 成员只能改自己那条
        if (path.size >= 4 && path[2] == "members") return path[3] == uid
        // 请求：房间里的成员，并且房主开着"允许成员控制"
        if (path.size >= 3 && path[2] == "req") {
            val member = (room.get("members") as? JsonObject)?.has(uid) == true
            return member && room.get("memberControl")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        }
        return false
    }

    /** 写完以后房间不能太大，房主也不能被改成别人 */
    private fun valid(uid: String, code: String, before: JsonObject?): Boolean {
        val room = read(listOf("rooms", code)) as? JsonObject ?: return true
        if (room.toString().length > MAX_ROOM_CHARS) return false
        val host = room.get("host")?.takeIf { it.isJsonPrimitive }?.asString ?: return false
        val oldHost = before?.get("host")?.takeIf { it.isJsonPrimitive }?.asString
        return host == uid || host == oldHost
    }

    /** 试着写：没权限 / 写完不合规就撤回，返回 false */
    private fun tryWrite(uid: String, path: List<String>, apply: () -> Unit, now: Long): Boolean {
        if (!canWrite(uid, path, now)) return false
        val code = path[1]
        val before = read(listOf("rooms", code))?.deepCopyCompat()
        apply()
        if (valid(uid, code, before as? JsonObject)) return true
        write(listOf("rooms", code), before)
        return false
    }

    /** Gson 2.2.4 没有 deepCopy：转成文字再读回来 */
    private fun JsonElement.deepCopyCompat(): JsonElement = com.google.gson.JsonParser().parse(toString())

    // ---------------------------------------------------------------- 消息

    fun handle(player: UUID, msg: JsonObject) {
        val uid = player.toString()
        val id = msg.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return
        val op = msg.get("op")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val path = segments(msg.get("path")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
        val now = System.currentTimeMillis()
        val reply = JsonObject().apply { addProperty("re", id); addProperty("status", 200) }
        val changed = ArrayList<List<String>>()
        synchronized(lock) {
            when (op) {
                "hello" -> {
                    reply.addProperty("protocol", PartyNet.PROTOCOL)
                    reply.addProperty("uid", uid)
                    reply.addProperty("now", now)
                }
                "time" -> reply.addProperty("now", now)
                "get" -> {
                    val v = read(path)
                    reply.add("value", v ?: JsonNull.INSTANCE)
                    reply.addProperty("etag", etag(v))
                }
                "put", "putIf" -> {
                    val value = resolve(msg.get("value"), now)
                    if (op == "putIf" && msg.get("etag")?.takeIf { it.isJsonPrimitive }?.asString != etag(read(path))) {
                        reply.addProperty("status", 412)
                    } else if (tryWrite(uid, path, { write(path, value) }, now)) {
                        reply.add("value", value ?: JsonNull.INSTANCE)
                        changed += path
                    } else reply.addProperty("status", 403)
                }
                "patch" -> {
                    val data = msg.get("value") as? JsonObject
                    if (data == null) reply.addProperty("status", 400)
                    else {
                        // 子项名字也可能是多级路径（"members/xxx/lastSeen"），每一项按自己的完整路径检查权限
                        val targets = data.entrySet().map { (k, v) -> (path + segments(k)) to resolve(v, now) }
                        if (targets.all { canWrite(uid, it.first, now) } && tryWrite(uid, path, { targets.forEach { (p, v) -> write(p, v) } }, now)) {
                            reply.add("value", data)
                            changed += path
                        } else reply.addProperty("status", 403)
                    }
                }
                "delete" -> {
                    if (tryWrite(uid, path, { write(path, null) }, now)) changed += path
                    else reply.addProperty("status", 403)
                }
                "listen" -> {
                    val sub = msg.get("sub")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    listeners.removeAll { it.player == player && it.sub == sub }
                    listeners += Listener(player, sub, path)
                }
                "unlisten" -> {
                    val sub = msg.get("sub")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    listeners.removeAll { it.player == player && it.sub == sub }
                }
                else -> reply.addProperty("status", 400)
            }
        }
        PartyNet.sendToPlayer(player, reply)
        // 新的监听先推一份现在的数据；写了东西就推给听着相关路径的人
        if (op == "listen") push(listeners(player, msg.get("sub")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0))
        for (p in changed) push(affected(p))
    }

    private fun listeners(player: UUID, sub: Int): List<Listener> = synchronized(lock) {
        listeners.filter { it.player == player && it.sub == sub }
    }

    /** 写了 [path]：监听路径在它上面或者下面的都要推 */
    private fun affected(path: List<String>): List<Listener> = synchronized(lock) {
        listeners.filter { l ->
            val n = minOf(l.path.size, path.size)
            l.path.subList(0, n) == path.subList(0, n)
        }
    }

    private fun push(targets: List<Listener>) {
        for (l in targets) {
            val data = synchronized(lock) { read(l.path)?.deepCopyCompat() }
            PartyNet.sendToPlayer(l.player, JsonObject().apply {
                addProperty("ev", l.sub)
                add("data", data ?: JsonNull.INSTANCE)
            })
        }
    }

    /** 玩家离开服务器：他开的房间关掉，在别人房间里的那条成员记录删掉 */
    fun playerLeft(player: UUID) {
        val uid = player.toString()
        val changed = ArrayList<List<String>>()
        synchronized(lock) {
            listeners.removeAll { it.player == player }
            val rooms = root.get("rooms") as? JsonObject ?: return@synchronized
            for ((code, v) in rooms.entrySet().toList()) {
                val room = v as? JsonObject ?: continue
                if (room.get("host")?.takeIf { it.isJsonPrimitive }?.asString == uid) {
                    write(listOf("rooms", code), null)
                    changed += listOf("rooms", code)
                } else if ((room.get("members") as? JsonObject)?.has(uid) == true) {
                    write(listOf("rooms", code, "members", uid), null)
                    changed += listOf("rooms", code, "members", uid)
                }
            }
        }
        if (changed.isNotEmpty()) DangoLogger.info("Listen Together: cleaned up after a player left the server")
        for (p in changed) push(affected(p))
    }
}
