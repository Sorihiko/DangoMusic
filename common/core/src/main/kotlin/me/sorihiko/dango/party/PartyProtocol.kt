package me.sorihiko.dango.party

/**
 * 客户端和服务器之间「一起听歌」的协议。
 *
 * 一个请求就是「方法 + 路径 + 可选的版本号 + 可选的 JSON」，服务器回「状态 + 服务器时间 + 版本号 + JSON」，
 * 语义照 Firebase 实时数据库：
 *   - GET 读一个路径（不存在时返回 JSON null）；PUT 整段写；PATCH 合并；DELETE 删；
 *   - 写进去的 `{".sv":"timestamp"}` 由服务器换成它的当前时间（各人电脑时钟不一样，进度同步要用同一个时间）；
 *   - GET 会带版本号（ETag），PUT 时可以带上它做「没被别人改过才写」（版本不对回 412）；
 *   - SUBSCRIBE 订阅一个路径，之后每次变化推一条「路径 + 新值」。
 *
 * [VERSION] 变了就说明两端的数据格式不兼容：服务器和客户端必须一起更新。
 */
object PartyProtocol {

    /** 协议号 */
    const val VERSION = 1

    const val OP_GET = 0
    const val OP_PUT = 1
    const val OP_PATCH = 2
    const val OP_DELETE = 3
    const val OP_PING = 4
    const val OP_SUBSCRIBE = 5
    const val OP_UNSUBSCRIBE = 6

    /** 一次请求 / 推送里正文的最大长度（字符）。房间里的队列最多几百首，256K 足够 */
    const val MAX_BODY = 256 * 1024

    /** 路径最大长度 */
    const val MAX_PATH = 256

    /** 日志里用的名字 */
    @JvmStatic
    fun opName(op: Int): String = when (op) {
        OP_GET -> "GET"
        OP_PUT -> "PUT"
        OP_PATCH -> "PATCH"
        OP_DELETE -> "DELETE"
        OP_PING -> "PING"
        OP_SUBSCRIBE -> "SUBSCRIBE"
        OP_UNSUBSCRIBE -> "UNSUBSCRIBE"
        else -> "OP$op"
    }
}
