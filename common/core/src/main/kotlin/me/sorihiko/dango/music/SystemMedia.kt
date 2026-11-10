package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.Writer
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 别的播放器（Spotify、Apple Music、网易云桌面版……）正在放的歌：通过 Windows 的系统媒体控制（SMTC）读出来。
 *
 * 用系统自带的 PowerShell 跑一个常驻小脚本（assets/dangomusic/system-media.ps1），
 * 有变化就吐一行 JSON；暂停 / 切歌 / 拖进度从 stdin 发给它。不加依赖，也不用打包 exe。
 * 读到的歌名拿去当前音源搜一下，配上了就有歌词和封面。
 */
object SystemMedia {

    /** Windows 10 起才有 SMTC（1809 之前的读不到会自己退出） */
    val supported: Boolean = run {
        val os = System.getProperty("os.name", "")
        val ver = System.getProperty("os.version", "").substringBefore('.').toIntOrNull() ?: 0
        os.startsWith("Windows") && ver >= 10
    }

    /** 设置里的开关；每 tick 写一次，变了才启动 / 关掉后台进程 */
    @Volatile var enabled = false
        set(v) {
            val on = v && supported
            if (field == on) return
            field = on
            if (on) start() else stop()
        }

    /** 歌词偏移（ms）：正数 = 歌词往后挪。别的播放器报的进度多少有点延迟，自己调 */
    @Volatile var offsetMs = 0L

    /** 正在放的歌（已经配过音源的；配不上的只有歌名歌手，没有歌词） */
    @Volatile var track: NeteaseTrack? = null
        private set
    @Volatile var playing = false
        private set

    // 进度：上一次对上的时间点（本机时钟）+ 之后过了多久
    @Volatile private var basePos = 0L
    @Volatile private var baseAt = 0L
    @Volatile private var endMs = 0L

    val positionMs: Long
        get() {
            if (track == null) return 0L
            var p = basePos + (if (playing) System.currentTimeMillis() - baseAt else 0L) + offsetMs
            val end = durationMs
            if (end > 0) p = p.coerceAtMost(end)
            return p.coerceAtLeast(0L)
        }

    val durationMs: Long get() = endMs.takeIf { it > 0 } ?: track?.durationMs ?: 0L

    /** 播放器上的按钮转给那个 App；它自己不支持的话就没反应 */
    fun togglePause() = send("toggle")
    fun next() = send("next")
    fun prev() = send("prev")
    fun seek(ms: Long) = send("seek ${(ms - offsetMs).coerceAtLeast(0L)}")

    // ---------------------------------------------------------------- 后台进程

    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var stdin: Writer? = null
    /** 每次启动 +1，旧的读线程看到代数变了就不再写状态 */
    @Volatile private var generation = 0
    private var failures = 0
    private val matcher = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-SystemMedia-Match").apply { isDaemon = true } }

    private val script: String? by lazy {
        try {
            SystemMedia::class.java.getResourceAsStream("/assets/dangomusic/system-media.ps1")?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) { null }
    }

    init {
        try { Runtime.getRuntime().addShutdownHook(Thread({ stop() }, "Dango-SystemMedia-Stop")) } catch (_: Exception) {}
    }

    private fun start() {
        val text = script ?: return
        val gen = synchronized(lock) { ++generation }
        Thread({ run(gen, text) }, "Dango-SystemMedia").apply { isDaemon = true }.start()
    }

    private fun stop() {
        synchronized(lock) {
            generation++
            try { stdin?.close() } catch (_: Exception) {}
            process?.destroy()
            process = null
            stdin = null
        }
        clear()
    }

    private fun run(gen: Int, text: String) {
        while (gen == generation) {
            var code = -1
            try {
                // -EncodedCommand 是 UTF-16LE 的 Base64：不用落临时文件，也不受执行策略影响
                val encoded = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_16LE))
                val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                    .redirectError(ProcessBuilder.Redirect.to(java.io.File("NUL")))   // Java 8 没有 Redirect.DISCARD
                    .start()
                synchronized(lock) {
                    if (gen != generation) { p.destroy(); return }
                    process = p
                    stdin = p.outputStream.writer(Charsets.US_ASCII)
                }
                DangoLogger.info("System media: reader started")
                BufferedReader(InputStreamReader(p.inputStream, Charsets.US_ASCII)).use { r ->
                    while (gen == generation) {
                        val line = r.readLine() ?: break
                        failures = 0
                        update(gen, line)
                    }
                }
                code = p.waitFor()
            } catch (e: Exception) {
                DangoLogger.warn("System media: reader failed: {}", e.toString())
            }
            if (gen != generation) return
            clear()
            // 2 = 系统没有 SMTC；连着失败几次也别一直重开
            if (code == 2 || ++failures >= 3) {
                DangoLogger.warn("System media: reader stopped (exit {})", code)
                return
            }
            try { Thread.sleep(5000) } catch (_: InterruptedException) { return }
        }
    }

    private fun send(cmd: String) {
        synchronized(lock) {
            val w = stdin ?: return
            try { w.write(cmd + "\n"); w.flush() } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- 状态

    /** 现在这首的标识（App + 歌名 + 歌手），换了才重新配歌 */
    @Volatile private var songKey = ""
    @Volatile private var lastPos = -1L
    @Volatile private var lastAt = -1L

    private fun clear() {
        songKey = ""
        track = null
        playing = false
        endMs = 0L
        lastPos = -1L
        lastAt = -1L
    }

    private fun update(gen: Int, line: String) {
        val o = try { JsonParser().parse(line).asJsonObject } catch (_: Exception) { return }
        val title = str(o, "title").trim()
        val status = str(o, "status")
        if (title.isEmpty() || (status != "Playing" && status != "Paused")) {
            if (songKey.isNotEmpty()) clear()
            return
        }
        // Apple Music 把专辑拼在歌手后面："歌手 — 专辑"
        val artist = str(o, "artist").substringBefore(" \u2014 ").trim()
        val key = str(o, "app") + "\n" + title + "\n" + artist
        val now = System.currentTimeMillis()
        val pos = num(o, "pos")
        val at = num(o, "at")
        val nowPlaying = status == "Playing"
        if (key != songKey) {
            songKey = key
            track = null
            lastPos = -1L
            lastAt = -1L
            basePos = 0L
            baseAt = now
            val end = num(o, "end")
            val source = MusicSources.selected
            matcher.execute {
                val found = try { match(title, artist, end, source) } catch (e: Exception) {
                    DangoLogger.warn("System media: search failed: {}", e.toString()); null
                }
                DangoLogger.info("System media: new song from {}, matched on {}: {}", str(o, "app"), source.key, found != null)
                if (gen == generation && songKey == key) track = found ?: placeholder(title, artist, end, source, key)
            }
        }
        endMs = num(o, "end")
        if (pos != lastPos || at != lastAt) {
            // App 报了新的进度：从它报的那一刻往后推。时间戳不靠谱（太旧、在未来）就当是现在报的
            lastPos = pos
            lastAt = at
            val age = now - at
            basePos = pos + if (nowPlaying && at > 0 && age in 0..600_000) age else 0L
            baseAt = now
        } else if (nowPlaying != playing) {
            // 只是暂停 / 继续，进度没报：暂停时把推到的位置定住，继续时从现在接着推
            if (!nowPlaying) basePos += now - baseAt
            baseAt = now
        }
        playing = nowPlaying
    }

    // ---------------------------------------------------------------- 配歌

    /** 拿"歌名 歌手"去当前音源搜，歌名对得上、歌手也对得上的才算；时长接近的优先 */
    private fun match(title: String, artist: String, durationMs: Long, source: MusicSource): NeteaseTrack? {
        val query = if (artist.isEmpty()) title else "$title $artist"
        val results = MusicSources.api(source).search(query).take(15)
        val wantTitle = norm(title)
        val wantTitleBare = norm(stripBrackets(title))
        val wantArtist = norm(artist)
        var best: NeteaseTrack? = null
        var bestScore = 0
        for (r in results) {
            val name = norm(r.name)
            val nameBare = norm(stripBrackets(r.name))
            val titleScore = when {
                name == wantTitle -> 4
                nameBare.isNotEmpty() && nameBare == wantTitleBare -> 3
                wantTitleBare.length >= 2 && (name.contains(wantTitleBare) || wantTitle.contains(nameBare)) -> 1
                else -> 0
            }
            if (titleScore == 0) continue
            val artistOk = wantArtist.isEmpty() || r.artists.any { a ->
                val n = norm(a)
                n.isNotEmpty() && (wantArtist.contains(n) || n.contains(wantArtist))
            }
            if (!artistOk) continue
            var score = titleScore * 10
            if (wantArtist.isNotEmpty()) score += 5
            if (durationMs > 0 && r.durationMs > 0) {
                val diff = abs(durationMs - r.durationMs)
                score += when { diff < 2000 -> 4; diff < 6000 -> 2; diff > 30_000 -> -6; else -> 0 }
            }
            if (score > bestScore) { bestScore = score; best = r }
        }
        return best
    }

    /** 配不上的：只显示歌名歌手；nativeId 以 smtc: 开头，歌词模块看到就不去查 */
    private fun placeholder(title: String, artist: String, durationMs: Long, source: MusicSource, key: String): NeteaseTrack {
        val artists = if (artist.isEmpty()) emptyList() else artist.split(Regex("\\s*[/,、&]\\s*|\\s+feat\\.?\\s+", RegexOption.IGNORE_CASE)).filter { it.isNotBlank() }
        return NeteaseTrack(0L, title, artists, durationMs, "", source, PLACEHOLDER_PREFIX + Integer.toHexString(key.hashCode()))
    }

    const val PLACEHOLDER_PREFIX = "smtc:"

    private fun stripBrackets(s: String) = s.replace(Regex("[(（\\[【][^)）\\]】]*[)）\\]】]"), "").replace(Regex("\\s+-\\s+.*$"), "")

    private fun norm(s: String): String = buildString {
        for (c in s.lowercase()) if (Character.isLetterOrDigit(c)) append(c)
    }

    private fun str(o: JsonObject, k: String): String {
        val e = o.get(k) ?: return ""
        return if (e.isJsonPrimitive) e.asString else ""
    }

    private fun num(o: JsonObject, k: String): Long {
        val e = o.get(k) ?: return 0L
        return try { if (e.isJsonPrimitive) e.asLong else 0L } catch (_: Exception) { 0L }
    }
}
