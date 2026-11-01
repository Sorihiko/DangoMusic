package me.sorihiko.dango.party

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一起听歌的连接方式：跟随系统代理（默认）/ 直连 / 手动填代理地址。
 * Java 默认不走系统代理（Clash 等只开"系统代理"、不开 TUN 时游戏连不上），所以这里自己读系统设置，
 * 每个请求显式传 [Proxy]；不改 JVM 全局的代理设置，音乐源、歌词、封面的请求不受影响。
 * 设置存在 config/dangomusic/party_proxy.json。
 */
object PartyProxy {

    enum class Mode(val key: String) { SYSTEM("system"), DIRECT("direct"), MANUAL("manual") }

    /** 手动代理连不上或地址不对（和目标服务器连不上区分开，界面提示不一样） */
    class ProxyUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** 一个代理地址；[label] 给界面和日志看，形如 "127.0.0.1:7890" 或 "socks5://127.0.0.1:7891" */
    class Route(val proxy: Proxy, val label: String)

    /**
     * 系统代理的检测结果。[route] 为 null 表示系统没设代理（直连）；
     * [pac] 表示系统用的是 PAC 自动配置脚本（不支持，只能用 ProxyServer 或者直连）；[error] 是读取失败的原因。
     */
    class SystemInfo(val route: Route?, val pac: Boolean, val error: String?)

    /** 系统设置多久重新读一次 */
    private const val SYSTEM_TTL_MS = 30_000L
    /** 读系统设置的命令最多等多久 */
    private const val DETECT_TIMEOUT_MS = 3_000L
    /** 系统代理连不上后，这段时间内直接直连，不再每次都先撞一下代理 */
    private const val PROXY_DOWN_MS = 30_000L

    /**
     * 默认走 DIRECT。
     *
     * 这个 mod 的 Direct 已经改成「走当前 MC 服务器的中转，服务器没装再退回 Firebase 直连」，
     * 所以它才是装上就该用的那个；SYSTEM/MANUAL 只在服务器没有、又确实需要代理连 Firebase 时才用。
     */
    @Volatile var mode = Mode.DIRECT
        private set
    @Volatile var manualAddress = ""
        private set

    @Volatile private var detected: SystemInfo? = null
    @Volatile private var detectedAt = 0L
    @Volatile private var downUntil = 0L
    /** 手动代理最近一次连不上的地址（界面提示用），连上后清空 */
    @Volatile var manualError = ""
        private set
    private val detectLock = Any()
    private val detecting = AtomicBoolean(false)
    private val loaded = AtomicBoolean(false)

    private val file: File? get() = try {
        DangoMusic.instance.clientDir.resolve("party_proxy.json").toFile()
    } catch (_: Throwable) {
        null
    }

    // ---------------------------------------------------------------- 设置

    private fun ensureLoaded() {
        if (!loaded.compareAndSet(false, true)) return
        try {
            val f = file ?: return
            if (!f.isFile) return
            val o = JsonParser().parse(f.readText(StandardCharsets.UTF_8)).asJsonObject
            val key = o.get("mode")?.asString ?: ""
            mode = Mode.values().firstOrNull { it.key == key } ?: Mode.DIRECT   // 存档里没有就按 Direct（走服务器）
            manualAddress = (o.get("address")?.asString ?: "").trim().take(200)
        } catch (_: Exception) {
        }
    }

    /** 改设置：马上生效（下一个请求就用新的方式），并重新检测系统代理 */
    fun set(newMode: Mode, address: String = manualAddress) {
        ensureLoaded()
        mode = newMode
        manualAddress = address.trim().take(200)
        downUntil = 0L
        manualError = ""
        try {
            val f = file
            if (f != null) {
                val o = JsonObject()
                o.addProperty("mode", newMode.key)
                o.addProperty("address", manualAddress)
                f.parentFile.mkdirs()
                f.writeText(o.toString(), StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {
        }
        if (newMode == Mode.SYSTEM) refreshAsync()
    }

    fun currentMode(): Mode {
        ensureLoaded()
        return mode
    }

    /** 系统代理连不上、正在临时直连 */
    val fallbackActive: Boolean get() = System.currentTimeMillis() < downUntil

    /** 界面用：拿缓存的系统检测结果（null 表示还在检测），过期时在后台重新检测，不卡界面 */
    fun systemInfo(): SystemInfo? {
        if (System.currentTimeMillis() - detectedAt > SYSTEM_TTL_MS) refreshAsync()
        return detected
    }

    /** 在后台线程重新读系统代理设置 */
    fun refreshAsync() {
        if (!detecting.compareAndSet(false, true)) return
        Thread({
            try {
                detectNow()
            } finally {
                detecting.set(false)
            }
        }, "Dango-ProxyDetect").apply { isDaemon = true }.start()
    }

    private fun detectNow(): SystemInfo = synchronized(detectLock) {
        val info = try {
            detectSystem()
        } catch (e: Exception) {
            SystemInfo(null, false, e.message ?: e.toString())
        }
        detected = info
        detectedAt = System.currentTimeMillis()
        downUntil = 0L
        info
    }

    // ---------------------------------------------------------------- 连接

    /** 现在应该走哪条路；只在后台线程调用（可能要读系统设置，最多等 [DETECT_TIMEOUT_MS]） */
    @Throws(IOException::class)
    fun route(): Route {
        ensureLoaded()
        return when (mode) {
            Mode.DIRECT -> DIRECT
            Mode.MANUAL -> parseAddress(manualAddress) ?: throw ProxyUnavailable("Invalid proxy address")
            Mode.SYSTEM -> {
                val info = if (System.currentTimeMillis() - detectedAt > SYSTEM_TTL_MS) detectNow() else detected
                if (info?.route == null || fallbackActive) DIRECT else info.route
            }
        }
    }

    /**
     * 按当前设置打开连接。[setup] 设置请求方法、超时、请求头等（在连接前调用），然后只建立连接，还没发请求。
     *
     * 跟随系统时，如果在**建立连接这一步**就失败了（代理没开、代理连不上目标），这时请求还没发出去，
     * 读写都可以安全地改用直连再连一次，并在 [PROXY_DOWN_MS] 内先直连。
     * 连上以后再出的错（发请求、读响应时）一律交给调用方，不在这里重试，免得写请求被送达两次。
     * 手动代理连不上时不退回直连，直接报错，免得玩家以为自己走了代理。
     */
    @Throws(IOException::class)
    fun connect(url: URL, setup: (HttpURLConnection) -> Unit): HttpURLConnection {
        val r = route()
        val conn = url.openConnection(r.proxy) as HttpURLConnection
        setup(conn)
        try {
            conn.connect()
            if (r !== DIRECT && mode == Mode.MANUAL) manualError = ""
            return conn
        } catch (e: IOException) {
            conn.disconnect()
            if (r === DIRECT) throw e
            if (mode == Mode.MANUAL) {
                manualError = r.label
                throw ProxyUnavailable("Proxy " + r.label + " unreachable: " + e, e)
            }
            if (!fallbackActive) DangoLogger.warn("Listen Together: system proxy {} unreachable ({}), connecting directly", r.label, e.toString())
            downUntil = System.currentTimeMillis() + PROXY_DOWN_MS
        }
        val direct = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        setup(direct)
        direct.connect()
        return direct
    }

    private val DIRECT = Route(Proxy.NO_PROXY, "")

    // ---------------------------------------------------------------- 解析

    /**
     * 解析代理地址："127.0.0.1:7890"（当 HTTP 代理）、"http://host:port"、"socks5://host:port"、"[::1]:7890"。
     * 端口必须写；不支持带账号密码的代理。格式不对返回 null。
     */
    @JvmStatic
    fun parseAddress(text: String, defaultType: Proxy.Type = Proxy.Type.HTTP): Route? {
        var s = text.trim()
        if (s.isEmpty() || s.contains('@') || s.any { it.isWhitespace() }) return null
        var type = defaultType
        val scheme = s.substringBefore("://", "").lowercase(Locale.ROOT)
        if (s.contains("://")) {
            type = when (scheme) {
                "http", "https" -> Proxy.Type.HTTP
                "socks", "socks4", "socks5", "socks5h" -> Proxy.Type.SOCKS
                else -> return null
            }
            s = s.substringAfter("://")
        }
        s = s.trimEnd('/')
        val host: String
        val portText: String
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0 || end + 1 >= s.length || s[end + 1] != ':') return null
            host = s.substring(1, end)
            portText = s.substring(end + 2)
        } else {
            if (s.count { it == ':' } != 1) return null
            host = s.substringBefore(':')
            portText = s.substringAfter(':')
        }
        if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ':' }) return null
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val shownHost = if (host.contains(':')) "[$host]" else host
        val label = (if (type == Proxy.Type.SOCKS) "socks5://" else "") + "$shownHost:$port"
        return Route(Proxy(type, InetSocketAddress.createUnresolved(host, port)), label)
    }

    /**
     * 解析 Windows 注册表 Internet Settings 的 `reg query` 输出。
     * ProxyServer 可能是 "127.0.0.1:7890"，也可能是 "http=…;https=…;socks=…"（按 https、http、socks 的顺序选）。
     */
    @JvmStatic
    fun parseWindowsRegistry(output: String): SystemInfo {
        val values = HashMap<String, String>()
        for (line in output.lines()) {
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size == 3 && parts[1].startsWith("REG_")) values[parts[0].lowercase(Locale.ROOT)] = parts[2].trim()
        }
        val enabled = values["proxyenable"]?.let { it == "0x1" || it == "1" } ?: false
        val pac = !values["autoconfigurl"].isNullOrBlank()
        val server = values["proxyserver"] ?: ""
        if (!enabled || server.isBlank()) return SystemInfo(null, pac, null)
        if (!server.contains('=')) {
            return parseAddress(server)?.let { SystemInfo(it, pac, null) } ?: SystemInfo(null, pac, "Unrecognized ProxyServer")
        }
        val byScheme = server.split(';').mapNotNull {
            val k = it.substringBefore('=', "").trim().lowercase(Locale.ROOT)
            val v = it.substringAfter('=', "").trim()
            if (k.isEmpty() || v.isEmpty()) null else k to v
        }.toMap()
        val route = byScheme["https"]?.let { parseAddress(it) }
            ?: byScheme["http"]?.let { parseAddress(it) }
            ?: byScheme["socks"]?.let { parseAddress(it, Proxy.Type.SOCKS) }
        return if (route != null) SystemInfo(route, pac, null) else SystemInfo(null, pac, "Unrecognized ProxyServer")
    }

    /** 解析 macOS `scutil --proxy` 的输出 */
    @JvmStatic
    fun parseMacScutil(output: String): SystemInfo {
        val values = HashMap<String, String>()
        for (line in output.lines()) {
            val k = line.substringBefore(" : ", "").trim()
            if (k.isNotEmpty()) values[k] = line.substringAfter(" : ").trim()
        }
        val pac = values["ProxyAutoConfigEnable"] == "1"
        fun pick(prefix: String, type: Proxy.Type): Route? {
            if (values[prefix + "Enable"] != "1") return null
            val host = values[prefix + "Proxy"] ?: return null
            val port = values[prefix + "Port"] ?: return null
            return parseAddress("$host:$port", type)
        }
        val route = pick("HTTPS", Proxy.Type.HTTP) ?: pick("HTTP", Proxy.Type.HTTP) ?: pick("SOCKS", Proxy.Type.SOCKS)
        return SystemInfo(route, pac, null)
    }

    /** 解析 HTTPS_PROXY / ALL_PROXY 这类环境变量 */
    @JvmStatic
    fun parseEnv(env: Map<String, String>): SystemInfo {
        val raw = listOf("HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy", "HTTP_PROXY", "http_proxy")
            .firstNotNullOfOrNull { env[it]?.takeIf { v -> v.isNotBlank() } } ?: return SystemInfo(null, false, null)
        return parseAddress(raw)?.let { SystemInfo(it, false, null) } ?: SystemInfo(null, false, "Unrecognized proxy variable")
    }

    private fun detectSystem(): SystemInfo {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return when {
            os.contains("win") -> {
                val reg = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\reg.exe")
                parseWindowsRegistry(run(reg.path, "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"))
            }
            os.contains("mac") -> {
                val mac = parseMacScutil(run("/usr/sbin/scutil", "--proxy"))
                if (mac.route != null || mac.pac) mac else parseEnv(System.getenv())
            }
            else -> parseEnv(System.getenv())
        }
    }

    /** 跑一个读设置的系统命令，最多等 [DETECT_TIMEOUT_MS] */
    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        try {
            p.outputStream.close()
            var out = ""
            val reader = Thread({
                out = try { String(p.inputStream.readBytes()) } catch (_: Exception) { "" }
            }, "Dango-ProxyDetectRead").apply { isDaemon = true }
            reader.start()
            if (!p.waitFor(DETECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw IOException("timed out")
            reader.join(1000)
            return out
        } finally {
            p.destroy()
        }
    }
}
