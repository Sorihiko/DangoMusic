package me.sorihiko.dango.music

import java.awt.image.BufferedImage

/**
 * 汽水音乐登录：游戏内显示二维码，用汽水音乐 App 扫。
 * 二维码由桥接进程从 passport 取 token，再用 scan_login URL 在本地生成（ZXing）。
 */
object SodaLogin {
    enum class State { IDLE, LOADING, WAITING, SCANNED, SUCCESS, ERROR }

    @Volatile var state = State.IDLE
        private set
    @Volatile var message = ""
        private set
    @Volatile var qrImage: BufferedImage? = null
        private set
    @Volatile var qrVersion = 0
        private set
    @Volatile private var generation = 0

    fun start() {
        val current = synchronized(this) {
            if (state == State.LOADING || state == State.WAITING || state == State.SCANNED) return
            ++generation
        }
        state = State.LOADING
        message = ""
        qrImage = null
        Thread({ run(current) }, "Dango-soda-login").apply { isDaemon = true; start() }
    }

    /** 启动时静默尝试一次（读已保存的会话或本机客户端），不生成二维码。 */
    fun connectQuietly() {
        if (state != State.IDLE && state != State.ERROR) return
        Thread({
            try {
                SodaMusicSource.connect()
            } catch (_: Exception) {
            }
        }, "Dango-soda-autoconnect").apply { isDaemon = true; start() }
    }

    fun cancel() {
        synchronized(this) {
            ++generation
            state = State.IDLE
            qrImage = null
            message = ""
        }
    }

    private fun run(token: Int) {
        try {
            val reply = SodaBridge.qrStart()
            if (!reply.ok) return fail(token, reply.error)
            val url = reply.text("qrUrl")
            if (url.isEmpty()) return fail(token, "Soda Music did not return a login QR code.")
            val image = NeteaseLogin.makeQr(url, 240)
            synchronized(this) {
                if (token != generation) return
                qrImage = image
                qrVersion++
                state = State.WAITING
            }
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(4000)
                if (synchronized(this) { token != generation }) return
                val poll = SodaBridge.qrPoll()
                if (!poll.ok) return fail(token, poll.error)
                // 被限流时拉长间隔，别继续顶着风控打。
                if (poll.flag("limited")) {
                    Thread.sleep(10_000)
                    continue
                }
                when (poll.text("status")) {
                    "scanned" -> synchronized(this) { if (token == generation) state = State.SCANNED }
                    "confirmed" -> {
                        if (!SodaMusicSource.connect()) return fail(token, "Soda Music login did not complete.")
                        synchronized(this) { if (token == generation) state = State.SUCCESS }
                        return
                    }
                    "expired" -> return fail(token, "The login QR code expired. Try again.")
                }
            }
            fail(token, "The login QR code expired. Try again.")
        } catch (e: Exception) {
            fail(token, e.message?.takeIf { it.isNotBlank() } ?: "Unable to log in to Soda Music.")
        }
    }

    private fun fail(token: Int, text: String) {
        synchronized(this) {
            if (token != generation) return
            state = State.ERROR
            message = text
        }
    }
}
