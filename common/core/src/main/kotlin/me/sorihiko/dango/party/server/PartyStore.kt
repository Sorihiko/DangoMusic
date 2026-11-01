package me.sorihiko.dango.party.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

import me.sorihiko.dango.party.PartyProtocol

import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Locale

/**
 * 服务器端的「一起听歌数据库」：一棵 JSON 树 + 版本号 + 订阅表。
 *
 * 只实现一起听真正用到的 Firebase 语义（见 [PartyProtocol]）。不依赖 Minecraft，
 * 可以单独拿出来测；服务器主线程通过 [handle] / [subscribe] 调用它。
 *
 * 所有方法都加锁：现在只有服务器主线程会碰，但请求处理、退服清理、以后可能的定时清理
 * 来自不同入口，锁一下不会错。
 */
object PartyStore {

    /** 路径最多几层，防止有人用深层嵌套把内存撑爆 */
    const val MAX_DEPTH = 8

    /** 最多同时存在多少个房间：防止有人一直建房把服务器内存刷爆 */
    const val MAX_ROOMS = 128

    /**
     * 一次请求的结果。
     * @param status HTTP 风格的状态码（200 / 400 / 403 / 412 / 500）
     * @param etag   这个路径现在的版本号，形如 `"7"`（和 Firebase 的 ETag 一样带引号）
     * @param value  写入后的值（GET 时是读到的值；删除后的路径是 null）
     * @param changedPath 这次请求真正改动到的路径（没改动就是 null），调用方据此广播
     */
    class Result(val status: Int, val etag: String, val value: JsonElement?, val changedPath: String?)

    /** 一条订阅：谁（玩家 UUID）用哪个订阅号听了哪个路径 */
    class Sub(val playerId: String, val subId: Int, val path: String)

    private val lock = Any()

    private var root = JsonObject()
    private val versions = HashMap<String, Long>()
    private var nextVersion = 1L
    private val subscriptions = LinkedHashMap<String, Sub>()

    // ---------------------------------------------------------------- 生命周期

    /** 服务器停了：房间只在内存里，下次开服重新建房 */
    fun reset() = synchronized(lock) {
        root = JsonObject()
        versions.clear()
        nextVersion = 1L
        subscriptions.clear()
    }

    // ---------------------------------------------------------------- 读

    fun get(path: String): JsonElement? = synchronized(lock) { value(normalize(path)) }

    fun etagOf(path: String): String = synchronized(lock) { etag(versionOfLocked(normalize(path))) }

    /**
     * 处理一个请求。[body] 已经解析好（没有正文就是 null）。
     * 服务器时间戳在写入时替换，所以写请求的返回值就是真正存进去的值。
     */
    fun handle(op: Int, path: String, etag: String, body: JsonElement?): Result = synchronized(lock) {
        val p = normalize(path)
        if (p.isEmpty() || p.split("/").size > MAX_DEPTH) {
            return Result(403, etagOf(p), null, null)
        }
        when (op) {
            PartyProtocol.OP_GET -> Result(200, etag(versionOfLocked(p)), value(p), null)

            PartyProtocol.OP_PUT, PartyProtocol.OP_PATCH -> {
                val current = etag(versionOfLocked(p))
                if (etag.isNotEmpty() && etag != current) {
                    return Result(412, current, null, null)
                }
                val incoming = resolveTimestamps(body)
                if (incoming == null || incoming.isJsonNull) {
                    // 和 Firebase 一样：写 null 等于删掉这个路径
                    deleteLocked(p)
                    return Result(200, etag(versionOfLocked(p)), null, p)
                }
                // 新建房间时限制总数（已经存在的房间照常更新）
                if (isRoomRoot(p) && value(p) == null && roomCount() >= MAX_ROOMS) {
                    return Result(503, current, null, null)
                }
                val written = if (op == PartyProtocol.OP_PATCH) patchLocked(p, incoming) else putLocked(p, incoming)
                if (written == null) return Result(400, current, null, null)
                Result(200, etag(versionOfLocked(p)), written, p)
            }

            PartyProtocol.OP_DELETE -> {
                deleteLocked(p)
                Result(200, etag(versionOfLocked(p)), null, p)
            }

            PartyProtocol.OP_PING -> Result(200, etag(versionOfLocked(p)), null, null)

            else -> Result(405, etag(versionOfLocked(p)), null, null)
        }
    }

    // ---------------------------------------------------------------- 订阅

    /** 订阅一个路径，返回它现在的数据（房间不存在时是 null）。重复订阅同一个号会覆盖旧的。 */
    fun subscribe(playerId: String, subId: Int, path: String): JsonElement? = synchronized(lock) {
        val p = normalize(path)
        subscriptions[subKey(playerId, subId)] = Sub(playerId, subId, p)
        value(p)
    }

    fun unsubscribe(playerId: String, subId: Int) = synchronized(lock) {
        subscriptions.remove(subKey(playerId, subId))
    }

    /** 这个玩家的所有订阅（退服时清掉） */
    fun dropSubscriptions(playerId: String) = synchronized(lock) {
        subscriptions.entries.removeIf { it.value.playerId == playerId }
    }

    /** 现在所有订阅的副本（广播时遍历，避免边发边改） */
    fun subscribers(): List<Sub> = synchronized(lock) { ArrayList(subscriptions.values) }

    /**
     * 玩家退服：把他从所有房间的成员列表里摘掉，同时清掉他的订阅。
     * 返回被改动到的路径（`rooms/<码>/members/<uuid>`），调用方据此广播。
     */
    fun dropPlayer(playerId: String): List<String> = synchronized(lock) {
        dropSubscriptions(playerId)
        val changed = ArrayList<String>()
        val rooms = root.get("rooms")
        if (rooms == null || !rooms.isJsonObject) return changed
        val roomsObj = rooms.asJsonObject
        val codes = ArrayList<String>()
        for (entry in roomsObj.entrySet()) codes.add(entry.key)
        for (code in codes) {
            val room = roomsObj.get(code)
            if (room == null || !room.isJsonObject) continue
            val members = room.asJsonObject.get("members")
            if (members == null || !members.isJsonObject) continue
            if (members.asJsonObject.remove(playerId) != null) {
                changed.add("rooms/$code/members/$playerId")
            }
        }
        for (path in changed) bump(path)
        changed
    }

    // ---------------------------------------------------------------- 广播辅助

    /**
     * 订阅路径 [subPath] 和变化路径 [changed] 的关系，返回推送时用的相对路径：
     *   - 变化就在订阅路径上 → "/"
     *   - 变化在订阅路径下面 → "/state"、"members/xxx" 这样
     *   - 变化是订阅路径的祖先（整个房间被删 / 整个子树被换） → "/"
     *   - 两条路径无关 → null（不要把别的房间的数据推进来）
     */
    @JvmStatic
    fun relative(subPath: String, changed: String): String? {
        val a = normalize(subPath)
        val b = normalize(changed)
        if (a == b) return "/"
        if (b.startsWith("$a/")) return "/" + b.substring(a.length + 1)
        if (a.startsWith("$b/")) return "/"
        return null
    }

    // ---------------------------------------------------------------- 内部：树操作

    /** 读路径；不存在的中间节点 / JSON null 都算没有 */
    private fun value(path: String): JsonElement? {
        var cur: JsonElement = root
        if (path.isEmpty()) return if (root.entrySet().isEmpty()) null else root
        for (seg in path.split("/")) {
            if (!cur.isJsonObject) return null
            cur = cur.asJsonObject.get(seg) ?: return null
            if (cur.isJsonNull) return null
        }
        return cur
    }

    private fun putLocked(path: String, incoming: JsonElement): JsonElement? {
        val value = copy(incoming)
        val segs = path.split("/")
        if (segs.size == 1) {
            root.add(segs[0], value)
        } else {
            var parent: JsonObject = root
            for (i in 0 until segs.size - 1) {
                val child = parent.get(segs[i])
                if (child == null || !child.isJsonObject) {
                    val created = JsonObject()
                    parent.add(segs[i], created)
                    parent = created
                } else {
                    parent = child.asJsonObject
                }
            }
            parent.add(segs[segs.size - 1], value)
        }
        bump(path)
        return value
    }

    /** PATCH：把对象里的每个键合并进去，其它键保留 */
    private fun patchLocked(path: String, incoming: JsonElement): JsonElement? {
        if (!incoming.isJsonObject) return putLocked(path, incoming)
        val existing = value(path)
        val target = if (existing != null && existing.isJsonObject) copy(existing).asJsonObject else JsonObject()
        for (entry in incoming.asJsonObject.entrySet()) {
            target.add(entry.key, entry.value)
        }
        return putLocked(path, target)
    }

    private fun deleteLocked(path: String) {
        val segs = path.split("/")
        if (segs.size == 1) {
            root.remove(segs[0])
        } else {
            var parent: JsonObject = root
            for (i in 0 until segs.size - 1) {
                val child = parent.get(segs[i])
                if (child == null || !child.isJsonObject) break
                parent = child.asJsonObject
            }
            parent.remove(segs[segs.size - 1])
        }
        bump(path)
    }

    // ---------------------------------------------------------------- 内部：版本 / 时间戳

    private fun versionOfLocked(path: String): Long = versions[path] ?: 0L

    private fun isRoomRoot(path: String): Boolean {
        val segs = path.split("/")
        return segs.size == 2 && segs[0] == "rooms" && segs[1].isNotEmpty()
    }

    private fun roomCount(): Int {
        val rooms = root.get("rooms") ?: return 0
        return if (rooms.isJsonObject) rooms.asJsonObject.entrySet().size else 0
    }

    private fun etag(version: Long): String = "\"" + version + "\""

    /** 这个路径和它所有祖先的版本都换一个新号，ETag 才有意义 */
    private fun bump(path: String) {
        val v = nextVersion++
        versions[path] = v
        var idx = path.lastIndexOf('/')
        while (idx > 0) {
            versions[path.substring(0, idx)] = v
            idx = path.substring(0, idx).lastIndexOf('/')
        }
    }

    /** 递归复制，同时把 {".sv":"timestamp"} 换成服务器当前时间 */
    private fun resolveTimestamps(element: JsonElement?): JsonElement? {
        if (element == null) return null
        if (element.isJsonNull) return JsonNull.INSTANCE
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            if (obj.entrySet().size == 1) {
                val sv = obj.get(".sv")
                if (sv != null && sv.isJsonPrimitive && sv.asJsonPrimitive.isString && sv.asString == "timestamp") {
                    return JsonPrimitive(System.currentTimeMillis())
                }
            }
            val out = JsonObject()
            for (entry in obj.entrySet()) {
                out.add(entry.key, resolveTimestamps(entry.value) ?: JsonNull.INSTANCE)
            }
            return out
        }
        if (element.isJsonArray) {
            val out = JsonArray()
            for (item in element.asJsonArray) {
                out.add(resolveTimestamps(item) ?: JsonNull.INSTANCE)
            }
            return out
        }
        return element
    }

    private fun copy(element: JsonElement): JsonElement = resolveTimestamps(element) ?: JsonNull.INSTANCE

    // ---------------------------------------------------------------- 工具

    @JvmStatic
    fun normalize(path: String?): String {
        var p = path ?: return ""
        p = p.trim()
        while (p.startsWith("/")) p = p.substring(1)
        while (p.endsWith("/")) p = p.substring(0, p.length - 1)
        return p
    }

    private fun subKey(playerId: String, subId: Int): String = playerId.lowercase(Locale.ROOT) + "#" + subId
}
