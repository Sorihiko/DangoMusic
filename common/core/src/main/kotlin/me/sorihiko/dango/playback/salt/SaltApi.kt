package me.sorihiko.dango.playback.salt

import com.google.gson.JsonObject
import com.google.gson.JsonParser

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * SaltBridge HTTP 客户端（协议 v1.0，Java 8 HttpURLConnection）。
 *
 * 约束：只连 127.0.0.1；关闭自动重定向；不发送 Origin；令牌只进 Authorization 头，
 * 不进日志与异常文案；响应读取有上限，避免异常响应撑爆内存。
 */
class SaltApi(
    private val portProvider: () -> Int = { SaltConfig.port },
    private val tokenProvider: () -> String = { SaltConfig.token() },
) {

    open class ApiException(val httpCode: Int, val apiCode: String, message: String) : IOException(message) {
        /** 令牌不对：提示重新配置 */
        class Unauthorized : ApiException(401, "unauthorized", "token invalid")
        /** 无权限（如曲库读取被撤销） */
        class Forbidden(apiCode: String) : ApiException(403, apiCode, "forbidden")
        class NotFound(apiCode: String) : ApiException(404, apiCode, "not found")
        /** 实例 / 代次不匹配：结果已过期 */
        class Conflict(apiCode: String) : ApiException(409, apiCode, "conflict")
        class BadRequest(apiCode: String) : ApiException(400, apiCode, "bad request")
        class TooManyRequests(val retryAfterSec: Int) : ApiException(429, "rate_limited", "rate limited")
        /** 网络不通 / 超时 / 连接拒绝等：可自动恢复 */
        class Transport(cause: Throwable) : ApiException(-1, "transport", "transport error")
        /** 响应形状不对 */
        class Malformed(detail: String) : ApiException(-2, "malformed", detail)
    }

    class Health(
        val ok: Boolean,
        val protocolVersion: String,
        val instanceId: String,
        val pluginVersion: String,
        val hostVersion: String,
        val servedAtMs: Long,
    )

    class Capabilities(
        val positionStaleAfterMs: Long,
        val inferredAnimationMaxMs: Long,
        val requestsPerSecond: Int,
    )

    /** /status 的聚合视图 */
    class Status(
        val instanceId: String,
        val seq: Long,
        val servedAtMs: Long,
        val generation: Long,
        val trackStatus: String,
        val hasTrack: Boolean,
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val state: String,
        val isPlaying: Boolean?,
        val reportedMs: Long?,
        val reportedAtMs: Long?,
        val animationMode: String,
        val animationReason: String,
        val animationMaxMs: Long,
        val lyricsStatus: String,
        val lyricsAssociation: String,
        val lyricsVersion: Long,
    )

    /** /lyrics 的聚合视图 */
    class LyricsBody(
        val instanceId: String,
        val generation: Long,
        val version: Long,
        val status: String,
        val association: String,
        val timingSanity: String?,
        val lines: List<Line>,
    ) {
        class Line(
            val startMs: Long,
            val endMs: Long,
            val text: String,
            val timesValid: Boolean,
            val cells: List<Cell>,
        )
        class Cell(val startMs: Long, val endMs: Long, val text: String)
    }

    fun health(): Health {
        val root = get("health", auth = false)
        return Health(
            ok = bool(root, "ok", false),
            protocolVersion = str(root, "protocolVersion"),
            instanceId = str(root, "instanceId"),
            pluginVersion = str(root, "pluginVersion"),
            hostVersion = str(root, "hostVersion"),
            servedAtMs = long(root, "servedAtMs"),
        )
    }

    fun capabilities(): Capabilities {
        val root = get("capabilities", auth = true)
        val limits = root.get("limits") as? JsonObject ?: JsonObject()
        return Capabilities(
            positionStaleAfterMs = long(limits, "positionStaleAfterMs", 3000L),
            inferredAnimationMaxMs = long(limits, "inferredAnimationMaxMs", 1500L),
            requestsPerSecond = int(limits, "requestsPerSecond", 50),
        )
    }

    fun status(): Status {
        val root = get("status", auth = true)
        val track = root.get("track") as? JsonObject
        val playback = root.get("playback") as? JsonObject ?: JsonObject()
        val position = playback.get("position") as? JsonObject ?: JsonObject()
        val animation = playback.get("animation") as? JsonObject ?: JsonObject()
        val lyrics = root.get("lyrics") as? JsonObject ?: JsonObject()
        val isPlaying = playback.get("isPlaying")?.let {
            if (it.isJsonNull) null else try { it.asBoolean } catch (_: Exception) { null }
        }
        return Status(
            instanceId = str(root, "instanceId"),
            seq = long(root, "seq"),
            servedAtMs = long(root, "servedAtMs"),
            generation = long(root, "generation"),
            trackStatus = str(root, "trackStatus"),
            hasTrack = track != null,
            trackId = track?.let { str(it, "id") } ?: "",
            title = track?.let { str(it, "title") } ?: "",
            artist = track?.let { str(it, "artist") } ?: "",
            album = track?.let { str(it, "album") } ?: "",
            durationMs = track?.let { long(it, "durationMs") } ?: 0L,
            state = str(playback, "state"),
            isPlaying = isPlaying,
            reportedMs = optLong(position, "reportedMs"),
            reportedAtMs = optLong(position, "reportedAtMs"),
            animationMode = str(animation, "mode"),
            animationReason = str(animation, "reason"),
            animationMaxMs = long(animation, "maxMs"),
            lyricsStatus = str(lyrics, "status"),
            lyricsAssociation = str(lyrics, "association"),
            lyricsVersion = long(lyrics, "version"),
        )
    }

    /** 正文请求带期望身份；不匹配时服务端 409 */
    fun lyrics(expectedInstanceId: String, expectedGeneration: Long): LyricsBody {
        val path = "lyrics?expectedInstanceId=$expectedInstanceId&expectedGeneration=$expectedGeneration"
        return parseLyrics(get(path, auth = true))
    }

    /** 二进制封面；202 是加载中，不是图片。仅供后台加载线程调用。 */
    fun cover(expectedInstanceId: String, expectedGeneration: Long): ByteArray? {
        val instance = java.net.URLEncoder.encode(expectedInstanceId, "UTF-8")
        val path = "cover?expectedInstanceId=$instance&expectedGeneration=$expectedGeneration"
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!Thread.currentThread().isInterrupted) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) return null
            val conn = URL(base() + path).openConnection() as HttpURLConnection
            var retryMs = 500L
            try {
                conn.connectTimeout = remainingMs.coerceAtMost(1500L).toInt().coerceAtLeast(1)
                conn.readTimeout = remainingMs.coerceAtMost(2500L).toInt().coerceAtLeast(1)
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "image/*")
                conn.setRequestProperty("Authorization", "Bearer " + tokenProvider())
                when (val code = conn.responseCode) {
                    200 -> {
                        if (conn.getHeaderField("X-SaltBridge-Instance-Id") != expectedInstanceId ||
                            conn.getHeaderField("X-SaltBridge-Generation")?.toLongOrNull() != expectedGeneration) return null
                        if (!conn.contentType.orEmpty().startsWith("image/", ignoreCase = true)) return null
                        return conn.inputStream.use { readBounded(it, 16 * 1024 * 1024) }
                    }
                    202 -> {
                        val bytes = conn.inputStream.use { readBounded(it, 16 * 1024) }
                        val body = JsonParser().parse(String(bytes, StandardCharsets.UTF_8)).asJsonObject
                        retryMs = long(body, "retryAfterMs", 500L).coerceAtLeast(50L)
                    }
                    403, 404, 409 -> return null
                    else -> throw mapError(code, conn, readBoundedQuiet(conn.errorStream, 16 * 1024))
                }
            } finally {
                conn.disconnect()
            }
            if (retryMs >= (deadline - System.nanoTime()) / 1_000_000L) return null
            Thread.sleep(retryMs)
        }
        return null
    }

    class ControlResult(val accepted: Boolean, val requestId: String, val deduplicated: Boolean)

    /**
     * 播放控制。
     * 202 = 已入队（不保证已执行）；200 = 相同 requestId 的重复提交（去重，不再执行）。
     * 同一逻辑请求重试必须复用原 requestId；新操作换新 ID。
     */
    fun control(
        requestId: String,
        expectedInstanceId: String,
        command: String,
        positionMs: Long? = null,
        expectedGeneration: Long? = null,
    ): ControlResult {
        val body = JsonObject()
        body.addProperty("requestId", requestId)
        body.addProperty("expectedInstanceId", expectedInstanceId)
        body.addProperty("command", command)
        if (positionMs != null) body.addProperty("positionMs", positionMs)
        if (expectedGeneration != null) body.addProperty("expectedGeneration", expectedGeneration)
        val root = post("control", body)
        return ControlResult(
            accepted = bool(root, "accepted", true),
            requestId = str(root, "requestId"),
            deduplicated = bool(root, "deduplicated", false),
        )
    }

    private fun post(path: String, body: JsonObject, maxBytes: Int = 64 * 1024): JsonObject {
        val conn = URL(base() + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 1500
            conn.readTimeout = 2500
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "DangoMusic")
            conn.setRequestProperty("Authorization", "Bearer " + tokenProvider())
            val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }

            val code = conn.responseCode
            if (code == 200 || code == 202) {
                val text = readBounded(conn.inputStream, maxBytes)
                return try {
                    val el = JsonParser().parse(String(text, StandardCharsets.UTF_8))
                    if (!el.isJsonObject) throw ApiException.Malformed("response is not an object")
                    el.asJsonObject
                } catch (e: ApiException) {
                    throw e
                } catch (e: Exception) {
                    throw ApiException.Malformed("response parse failed")
                }
            }
            throw mapError(code, conn, readBoundedQuiet(conn.errorStream, 16 * 1024))
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** 响应 JSON → LyricsBody（测试与 HTTP 路径共用） */
        fun parseLyrics(root: JsonObject): LyricsBody {
            val lines = mutableListOf<LyricsBody.Line>()
            val arr = root.get("lines")
            if (arr != null && arr.isJsonArray) {
                for (el in arr.asJsonArray) {
                    val o = el as? JsonObject ?: continue
                    val cells = mutableListOf<LyricsBody.Cell>()
                    val cellArr = o.get("cells")
                    if (cellArr != null && cellArr.isJsonArray) {
                        for (c in cellArr.asJsonArray) {
                            val co = c as? JsonObject ?: continue
                            cells += LyricsBody.Cell(longOf(co, "startMs"), longOf(co, "endMs"), strOf(co, "text"))
                        }
                    }
                    lines += LyricsBody.Line(
                        startMs = longOf(o, "startMs"),
                        endMs = longOf(o, "endMs"),
                        text = strOf(o, "text"),
                        timesValid = boolOf(o, "timesValid", true),
                        cells = cells,
                    )
                }
            }
            return LyricsBody(
                instanceId = strOf(root, "instanceId"),
                generation = longOf(root, "generation"),
                version = longOf(root, "version"),
                status = strOf(root, "status"),
                association = strOf(root, "association"),
                timingSanity = root.get("timingSanity")?.let { if (it.isJsonNull) null else it.asString },
                lines = lines,
            )
        }

        internal fun strOf(o: JsonObject, key: String): String {
            val e = o.get(key) ?: return ""
            return if (e.isJsonNull || !e.isJsonPrimitive) "" else try { e.asString } catch (_: Exception) { "" }
        }

        internal fun longOf(o: JsonObject, key: String, def: Long = 0L): Long {
            val e = o.get(key) ?: return def
            return if (e.isJsonNull) def else try { e.asLong } catch (_: Exception) { def }
        }

        internal fun boolOf(o: JsonObject, key: String, def: Boolean): Boolean {
            val e = o.get(key) ?: return def
            return if (e.isJsonNull) def else try { e.asBoolean } catch (_: Exception) { def }
        }
    }

    private fun base(): String = "http://127.0.0.1:" + portProvider() + "/api/v1/"

    private fun get(path: String, auth: Boolean, maxBytes: Int = 512 * 1024): JsonObject {
        val conn = URL(base() + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 1500
            conn.readTimeout = 2500
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "DangoMusic")
            if (auth) conn.setRequestProperty("Authorization", "Bearer " + tokenProvider())
            // 刻意不设 Origin：服务端拒绝任何带 Origin 的请求

            val code = conn.responseCode
            if (code == 200) {
                val body = readBounded(conn.inputStream, maxBytes)
                return try {
                    val el = JsonParser().parse(String(body, StandardCharsets.UTF_8))
                    if (!el.isJsonObject) throw ApiException.Malformed("response is not an object")
                    el.asJsonObject
                } catch (e: ApiException) {
                    throw e
                } catch (e: Exception) {
                    throw ApiException.Malformed("response parse failed")
                }
            }
            throw mapError(code, conn, readBoundedQuiet(conn.errorStream, 16 * 1024))
        } finally {
            conn.disconnect()
        }
    }

    private fun mapError(code: Int, conn: HttpURLConnection, errBody: ByteArray?): Exception {
        val apiCode = parseErrorCode(errBody) ?: when (code) {
            401 -> "unauthorized"
            403 -> "forbidden"
            404 -> "not_found"
            else -> "http_$code"
        }
        return when (code) {
            400, 413, 414, 415 -> ApiException.BadRequest(apiCode)
            401 -> ApiException.Unauthorized()
            403 -> ApiException.Forbidden(apiCode)
            404 -> ApiException.NotFound(apiCode)
            409 -> ApiException.Conflict(apiCode)
            429 -> {
                val retry = (conn.getHeaderField("Retry-After")?.toIntOrNull() ?: 1).coerceIn(1, 60)
                ApiException.TooManyRequests(retry)
            }
            else -> ApiException.Malformed("http $code $apiCode")
        }
    }

    private fun parseErrorCode(body: ByteArray?): String? {
        if (body == null || body.isEmpty()) return null
        return try {
            val root = JsonParser().parse(String(body, StandardCharsets.UTF_8)).asJsonObject
            val err = root.get("error") as? JsonObject ?: return null
            val code = err.get("code") ?: return null
            if (code.isJsonNull) null else code.asString
        } catch (_: Exception) {
            null
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw ApiException.Malformed("response too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readBoundedQuiet(input: InputStream?, maxBytes: Int): ByteArray? = try {
        if (input == null) null else readBounded(input, maxBytes)
    } catch (_: Exception) {
        null
    }

    private fun str(o: JsonObject, key: String): String {
        val e = o.get(key) ?: return ""
        return if (e.isJsonNull || !e.isJsonPrimitive) "" else try { e.asString } catch (_: Exception) { "" }
    }

    private fun long(o: JsonObject, key: String, def: Long = 0L): Long {
        val e = o.get(key) ?: return def
        return if (e.isJsonNull) def else try { e.asLong } catch (_: Exception) { def }
    }

    private fun optLong(o: JsonObject, key: String): Long? {
        val e = o.get(key) ?: return null
        return if (e.isJsonNull) null else try { e.asLong } catch (_: Exception) { null }
    }

    private fun int(o: JsonObject, key: String, def: Int): Int {
        val e = o.get(key) ?: return def
        return if (e.isJsonNull) def else try { e.asInt } catch (_: Exception) { def }
    }

    private fun bool(o: JsonObject, key: String, def: Boolean): Boolean {
        val e = o.get(key) ?: return def
        return if (e.isJsonNull) def else try { e.asBoolean } catch (_: Exception) { def }
    }
}
