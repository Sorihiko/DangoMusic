package me.sorihiko.dango.lyrics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** 逐字歌词里的一个字 / 词 */
class LyricWord(val startMs: Long, val durMs: Long, val text: String)

/** @param words 网易云逐字歌词（yrc）里这一句的每个字 / 词的时间；普通 LRC 歌词为 null
 *  @param endMs 行结束时间；null 表示未知（沿用旧行为：由下一行开始推断）。Salt 歌词提供真实结束时间
 *  @param timesValid 该行时间是否可信；false 的行不用于同步定位（Salt 协议 5.4） */
class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord>? = null,
    val endMs: Long? = null,
    val timesValid: Boolean = true,
)

class Lyrics(rawLines: List<LyricLine>, val sourceName: String) {
    /** 去掉"纯音乐，请欣赏"这类提示（网易云给没歌词的歌塞的一句话） */
    private val raw: List<LyricLine> = rawLines.filterNot { isPlaceholder(it.text) }

    companion object {
        fun isPlaceholder(text: String): Boolean {
            val t = text.filter { it.isLetterOrDigit() }.lowercase()
            if (t.isEmpty()) return false
            return t == "纯音乐" || t.startsWith("纯音乐请欣赏") || "没有填词的纯音乐" in t ||
                t == "instrumental" || t == "纯音乐请您欣赏"
        }
    }

    /** 网易云常把两句写在同一行（中间隔一个空格），拆成两句的版本 */
    private val split: List<LyricLine> by lazy { LyricSplit.split(raw) }

    /** 当前用的歌词行：开了 Split lines 就是拆开后的 */
    val lines: List<LyricLine> get() = if (LyricSplit.enabled) split else raw

    /** 有没有可用于同步定位的行（整首 timesValid=false 时无法跟唱） */
    val hasSyncableTimes: Boolean get() = lines.any { it.timesValid }

    /**
     * 最后一句「时间可信」且开始时间 <= pos 的下标，没有则 -1。
     * timesValid=false 的行只用于展示顺序，不参与同步定位（Salt 协议 5.4）。
     */
    fun indexAt(pos: Long): Int {
        var ans = -1
        for ((i, line) in lines.withIndex()) {
            if (!line.timesValid) continue
            if (line.timeMs <= pos) ans = i else break
        }
        return ans
    }
}

/**
 * 把"两句写在一行"的歌词拆开：
 *   今はまだ知らない 出会ってすらいない  →  今はまだ知らない / 出会ってすらいない
 * 只在空格两边都是中日韩文字时拆（英文本来就用空格分词，不拆）；全角空格也拆。
 * 有逐字时间（yrc）时，后半句从它第一个字开唱时出现；普通 LRC 按字数比例估时间。
 * 拆出来太短（不到 2 个字或不到 0.5 秒）的就并回前一半，不拆。
 */
object LyricSplit {
    /** Lyrics 模块的 Split lines 开关 */
    @Volatile var enabled = true

    private const val MIN_CHARS = 2
    private const val MIN_MS = 500L

    private fun isCjk(c: Char) = c.code >= 0x2E80 && c != '\u3000'

    /** text 里可以拆开的位置：返回每段的起始下标（第一段是 0） */
    private fun cuts(text: String): List<Int> {
        val starts = mutableListOf(0)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ' ' || c == '\u3000') {
                var j = i
                var fullWidth = false
                while (j < text.length && (text[j] == ' ' || text[j] == '\u3000')) {
                    if (text[j] == '\u3000') fullWidth = true
                    j++
                }
                val prev = text.substring(0, i).trimEnd().lastOrNull()
                val next = text.getOrNull(j)
                if (prev != null && next != null && (fullWidth || (isCjk(prev) && isCjk(next)))) starts += j
                i = j
            } else {
                i++
            }
        }
        return starts
    }

    private class Seg(var text: String, var startMs: Long, var words: MutableList<LyricWord>?) {
        val chars get() = text.count { !it.isWhitespace() }
    }

    fun split(lines: List<LyricLine>): List<LyricLine> {
        val out = ArrayList<LyricLine>(lines.size)
        for ((li, line) in lines.withIndex()) {
            val nextMs = lines.getOrNull(li + 1)?.timeMs
            val segs = segments(line, nextMs)
            if (segs.size <= 1) {
                out += line
                continue
            }
            // 太短的并回前一段
            val merged = mutableListOf<Seg>()
            for (sg in segs) {
                val prev = merged.lastOrNull()
                if (prev != null && (sg.chars < MIN_CHARS || prev.chars < MIN_CHARS || sg.startMs - prev.startMs < MIN_MS)) {
                    prev.text = prev.text + " " + sg.text
                    sg.words?.let { w -> prev.words?.addAll(w) }
                } else {
                    merged += sg
                }
            }
            // 最后一段离下一句太近也并回去
            if (merged.size > 1 && nextMs != null && nextMs - merged.last().startMs < MIN_MS) {
                val last = merged.removeAt(merged.size - 1)
                val prev = merged.last()
                prev.text = prev.text + " " + last.text
                last.words?.let { w -> prev.words?.addAll(w) }
            }
            if (merged.size <= 1) {
                out += line
                continue
            }
            for (sg in merged) out += LyricLine(sg.startMs, sg.text, sg.words)
        }
        out.sortBy { it.timeMs }
        return out
    }

    private fun segments(line: LyricLine, nextMs: Long?): List<Seg> {
        val words = line.words
        if (words != null) {
            // 逐字歌词：按每个字在整行里的位置分到各段，段的开始时间 = 段里第一个字的时间
            val full = words.joinToString("") { it.text }
            val starts = cuts(full)
            if (starts.size <= 1) return emptyList()
            val segs = starts.map { Seg("", Long.MAX_VALUE, mutableListOf()) }
            var pos = 0
            for (w in words) {
                val firstChar = w.text.indexOfFirst { !it.isWhitespace() }
                val at = pos + if (firstChar >= 0) firstChar else 0
                val k = starts.indexOfLast { it <= at }.coerceAtLeast(0)
                segs[k].words!!.add(w)
                if (firstChar >= 0 && w.startMs < segs[k].startMs) segs[k].startMs = w.startMs
                pos += w.text.length
            }
            val ok = segs.filter { it.words!!.any { w -> w.text.isNotBlank() } }
            ok.forEach { it.text = it.words!!.joinToString("") { w -> w.text }.trim() }
            return if (ok.size <= 1) emptyList() else ok
        }
        // 普通 LRC：只有整行开始时间，按字数比例估每段的开始时间。
        // 有真实行结束（endMs，如 Salt 歌词）时用它作边界，不用下一行开始推断，避免污染真实时间轴。
        val text = line.text
        val starts = cuts(text)
        if (starts.size <= 1) return emptyList()
        val parts = starts.mapIndexed { i, st -> text.substring(st, starts.getOrNull(i + 1) ?: text.length).trim() }
        val counts = parts.map { p -> p.count { !it.isWhitespace() } }
        val total = counts.sum().coerceAtLeast(1)
        val lineEnd = line.endMs ?: nextMs ?: (line.timeMs + total * 300L)
        val dur = (lineEnd - line.timeMs).coerceAtLeast(0L)
        val singing = dur * 0.85                     // 下一句前通常有一点空白
        var before = 0
        return parts.mapIndexed { i, p ->
            val sg = Seg(p, line.timeMs + (singing * before / total).toLong(), null)
            before += counts[i]
            sg
        }
    }
}

/** 按网易云歌曲 ID 取歌词（逐字 yrc 优先，没有就用普通 LRC） */
object LyricsProvider {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36 DangoMusic"

    /**
     * 按网易云歌曲 ID 取歌词。优先拿逐字歌词（yrc，每个字都有时间，文闪用），
     * 拿不到再用普通 LRC。
     */
    fun neteaseLyricById(id: Long): Lyrics? {
        var lrcText: String? = null
        var yrcText: String? = null
        val urls = listOf(
            "https://music.163.com/api/song/lyric/v1?id=$id&cp=false&lv=0&kv=0&tv=0&rv=0&yv=0&ytv=0&yrv=0",
            "https://music.163.com/api/song/lyric?id=$id&lv=-1&kv=-1&tv=-1&yv=-1",
        )
        for (url in urls) {
            if (lrcText != null && yrcText != null) break
            try {
                val body = httpGet(url, referer = "https://music.163.com/") ?: continue
                val root = JsonParser().parse(body)
                if (!root.isJsonObject) continue
                val o = root.asJsonObject
                if (lrcText == null) lrcText = lyricField(o, "lrc")
                if (yrcText == null) yrcText = lyricField(o, "yrc")
            } catch (e: Exception) {
                DangoLogger.warn("NetEase lyric request failed: {}", e.toString())
            }
        }
        yrcText?.let { y ->
            val lines = parseYrc(y)
            if (lines.isNotEmpty()) return Lyrics(lines, "NetEase")
        }
        val lines = lrcText?.let { parseLrc(it) } ?: return null
        return if (lines.isEmpty()) null else Lyrics(lines, "NetEase")
    }

    private fun lyricField(o: JsonObject, key: String): String? {
        val v = (o.get(key) as? JsonObject)?.get("lyric") ?: return null
        if (v.isJsonNull) return null
        return v.asString.takeIf { it.isNotBlank() }
    }

    private val yrcLine = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val yrcWord = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")

    /**
     * 网易云逐字歌词：
     *   [16210,3460](16210,670,0)还(16880,410,0)没(17290,600,0)有…
     * 每行 [开始,时长]，每个字 (开始,时长,0)字。以 { 开头的行是作词作曲之类的信息，跳过。
     */
    fun parseYrc(text: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (raw in text.lines()) {
            val m = yrcLine.find(raw.trim()) ?: continue
            val start = m.groupValues[1].toLong()
            val words = yrcWord.findAll(m.groupValues[3]).mapNotNull { w ->
                val t = w.groupValues[3]
                if (t.isEmpty()) null else LyricWord(w.groupValues[1].toLong(), w.groupValues[2].toLong(), t)
            }.toList()
            if (words.isEmpty()) continue
            val lineText = words.joinToString("") { it.text }.trim()
            if (lineText.isEmpty() || credit.containsMatchIn(lineText)) continue
            out += LyricLine(start, lineText, words)
        }
        out.sortBy { it.timeMs }
        return out
    }

    // ---------------------------------------------------------------- LRC 解析

    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val credit = Regex(
        """^\s*(作词|作曲|编曲|制作人|制作|混音|母带|和声|吉他|贝斯|鼓|弦乐|录音|监制|出品|发行|统筹|词|曲|OP|SP|Lyricist|Lyrics by|Composer|Composed by|Producer|Produced by|Arranger|Arranged by)\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )

    internal fun isCredit(text: String): Boolean = credit.containsMatchIn(text)

    fun parseLrc(text: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (raw in text.lines()) {
            val tags = mutableListOf<Long>()
            var rest = raw.trim()
            while (true) {
                val m = timeTag.find(rest) ?: break
                if (m.range.first != 0) break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                tags += min * 60_000 + sec * 1000 + frac
                rest = rest.substring(m.range.last + 1)
            }
            if (tags.isEmpty()) continue
            val lyric = rest.trim()
            if (credit.containsMatchIn(lyric)) continue
            for (t in tags) out += LyricLine(t, lyric)
        }
        out.sortBy { it.timeMs }
        return out
    }

    // ---------------------------------------------------------------- HTTP

    private fun httpGet(url: String, referer: String? = null): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json")
            if (referer != null) conn.setRequestProperty("Referer", referer)
            val code = conn.responseCode
            if (code !in 200..299) return null
            return conn.inputStream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
