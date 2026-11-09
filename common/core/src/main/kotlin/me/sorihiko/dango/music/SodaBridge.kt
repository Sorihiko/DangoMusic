package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 汽水音乐桥接进程：签名只能由客户端自带的 bdms.node 生成，JVM 调不了，
 * 所以用一个常驻的 Node 子进程（node bridge.js）负责“读登录态 + 签名 + 取播放信息”。
 * Cookie 只留在子进程里，不回传给 Java。
 */
internal object SodaBridge {
    class Reply(val ok: Boolean, val body: JsonObject?, val error: String) {
        fun text(key: String): String = body?.text(key).orEmpty()
        fun flag(key: String): Boolean = body?.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
    }

    private val lock = Any()
    private val replies = LinkedBlockingQueue<JsonObject>()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var nextId = 0

    fun status(): Reply = call("status", null, 20_000)

    fun track(trackId: String): Reply = call("track", JsonObject().apply { addProperty("trackId", trackId) }, 120_000)

    fun qrStart(): Reply = call("qrStart", null, 30_000)

    fun qrPoll(): Reply = call("qrPoll", null, 30_000)

    fun logout(): Reply = call("logout", null, 10_000)

    fun api(method: String, host: String, path: String, query: JsonObject? = null, body: JsonObject? = null): Reply {
        val payload = JsonObject().apply {
            addProperty("method", method)
            addProperty("host", host)
            addProperty("path", path)
            if (query != null) add("query", query)
            if (body != null) add("body", body)
        }
        return call("api", payload, 30_000)
    }

    fun close() = synchronized(lock) { reset() }

    /** 桥接进程是否已经就绪过（界面据此提示“正在连接”） */
    fun running(): Boolean = process?.isAlive == true

    private fun call(op: String, payload: JsonObject?, timeoutMs: Long): Reply = synchronized(lock) {
        var error = ""
        for (attempt in 0..1) {
            try {
                if (process?.isAlive != true) start().also { process = it }
                val output = writer ?: throw IllegalStateException("Soda Music bridge is not running")
                val id = ++nextId
                val request = JsonObject().apply {
                    addProperty("id", id)
                    addProperty("op", op)
                    if (payload != null) for ((key, value) in payload.entrySet()) add(key, value)
                }
                output.write(request.toString())
                output.newLine()
                output.flush()
                return await(id, timeoutMs)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                reset()
            }
        }
        Reply(false, null, error.ifEmpty { "Soda Music bridge is unavailable" })
    }

    private fun await(id: Int, timeoutMs: Long): Reply {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return Reply(false, null, "Soda Music bridge timed out")
            val reply = replies.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            val replyId = reply.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
            if (replyId != id) continue
            val ok = reply.get("ok")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val message = reply.get("error")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            return Reply(ok, reply, message.ifEmpty { if (ok) "" else "Soda Music request failed" })
        }
    }

    private fun start(): Process {
        val script = scriptFile()
        val node = nodeCommand()
        val builder = ProcessBuilder(node, script.absolutePath)
        // 某些 Electron 启动器会把这个变量带进子进程，node 会把它当成 Electron 模式。
        builder.environment().remove("ELECTRON_RUN_AS_NODE")
        System.getProperty("dangomusic.sodaClient")?.takeIf { it.isNotBlank() }?.let { builder.environment()["QS_CLIENT_BASE"] = it }
        builder.environment()["QS_SESSION_FILE"] = DangoMusic.instance.clientDir.resolve("soda/session.txt").toString()
        val started = builder.start()
        writer = BufferedWriter(OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8))
        Thread({ readLoop(started) }, "Dango-SodaBridge").apply { isDaemon = true; start() }
        Thread({
            try {
                started.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.filter { it.isNotBlank() }.forEach { DangoLogger.warn("Soda bridge: {}", it.take(300)) }
                }
            } catch (_: Exception) {
            }
        }, "Dango-SodaBridge-Err").apply { isDaemon = true; start() }
        replies.clear()
        return started
    }

    private fun readLoop(started: Process) {
        try {
            started.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val json = try { JsonParser().parse(line).asJsonObject } catch (_: Exception) { continue }
                    replies.offer(json)
                }
            }
        } catch (_: Exception) {
        } finally {
            DangoLogger.warn("Soda Music bridge exited: {}", try { started.exitValue() } catch (_: IllegalThreadStateException) { -1 })
        }
    }

    private fun reset() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        process?.destroy()
        process = null
        replies.clear()
    }

    private fun scriptFile(): File {
        val directory = DangoMusic.instance.clientDir.resolve("soda")
        Files.createDirectories(directory)
        val target = directory.resolve("bridge.js")
        val bytes = SodaBridge::class.java.getResourceAsStream("/assets/dangomusic/soda/bridge.js")?.use { it.readBytes() }
            ?: throw IllegalStateException("Soda Music bridge resource is missing")
        if (!Files.isRegularFile(target) || Files.size(target) != bytes.size.toLong()) {
            val temporary = target.resolveSibling("bridge.js.tmp")
            Files.write(temporary, bytes)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target.toFile()
    }

    private fun nodeCommand(): String {
        val candidates = mutableListOf("node", "C:\\Program Files\\nodejs\\node.exe")
        System.getenv("LOCALAPPDATA")?.let { candidates += "$it\\Programs\\nodejs\\node.exe" }
        System.getenv("ProgramFiles")?.let { candidates += "$it\\nodejs\\node.exe" }
        for (candidate in candidates) {
            try {
                val probe = ProcessBuilder(candidate, "--version").redirectErrorStream(true).start()
                val finished = probe.waitFor(5, TimeUnit.SECONDS)
                if (finished && probe.exitValue() == 0) return candidate
                probe.destroy()
            } catch (_: Exception) {
            }
        }
        throw SourceAccessException("Soda Music needs Node.js 22 or newer. Install Node.js and restart the game.")
    }
}
