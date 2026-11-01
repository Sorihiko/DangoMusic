package me.sorihiko.dango.platform

/**
 * 「一起听歌」在 Minecraft 网络上的通道。
 *
 * 客户端把原本要发给 Firebase 的那些请求（读、写、删、订阅）打包交给服务器，
 * 服务器用一个内存里的 JSON 树当数据库，再把变化推回房间里的所有人。
 * 这样只要服务器装了这个 mod，就不用 Firebase、不用代理、也不用开端口。
 *
 * 平台层实现（目前是 Forge 1.20.1）；没有实现的平台返回 null，「一起听」仍然走 Firebase。
 * 方法可能从后台线程调用（一起听有自己的工作线程），实现方负责回到游戏主线程再发包。
 */
interface PartyLink {

    /** 服务器装了这个 mod 并且已经握手（能收发一起听的包） */
    val available: Boolean

    /** 服务器声明的协议号；0 表示还没收到服务器的信息 */
    val protocol: Int

    /** 服务器显示名（界面用，可能是空串） */
    val serverName: String

    /** 本机在这个服务器里的身份（玩家 UUID 字符串） */
    val playerId: String

    /**
     * 发一个请求。返回 false 表示现在发不出去（没连服务器 / 服务器没装本 mod / 已经断线）。
     * 结果通过 [Handler.onResponse] 回来。
     */
    fun send(op: Int, id: Long, path: String, etag: String, body: String): Boolean

    /** 注册收包回调（传 null 解除）。实现方收到包时原样转发，不要在这里做耗时操作。 */
    fun bind(handler: Handler?)

    interface Handler {

        /** 一个请求的结果。[id] 是发请求时用的编号，[serverTime] 是服务器当时的时间（毫秒） */
        fun onResponse(id: Long, status: Int, serverTime: Long, etag: String, body: String)

        /** 订阅推送。[subId] 是订阅时用的编号，[path] 是相对订阅路径的位置（"/" 表示整个路径） */
        fun onEvent(subId: Long, path: String, data: String)
    }
}

/** 平台层在客户端启动时把 [PartyLink] 放进来；没放（或服务端不支持）就是 null。 */
object PartyLinks {

    @Volatile
    var link: PartyLink? = null
}
