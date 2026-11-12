package me.sorihiko.dango.music

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider

/** 云盘上传可能有自己的歌词；只用已核对的标识后备，不能按歌名猜另一首。 */
internal class NeteaseLyrics @JvmOverloads constructor(
    private val cloud: (Long) -> JsonObject?,
    private val catalog: (Long) -> Lyrics?,
    private val lookup: (NeteaseTrack) -> NeteaseTrack? = { null },
) {
    fun resolve(track: NeteaseTrack): Lyrics? {
        if (!track.cloud) return catalog(track.id)?.takeIf { it.lines.isNotEmpty() }
        val upload = track.cloudId.takeIf { it > 0 } ?: track.id
        try {
            cloud(upload)?.let { parse(it) }?.let { return it }
        } catch (_: Exception) { DangoLogger.warn("NetEase lyric stage=cloud request failed") }
        val verified = try { lookup(track) } catch (_: Exception) { null }
        val ids = linkedSetOf<Long>()
        if (verified != null && (verified.id == track.id || verified.cloudId == upload)) ids.add(verified.id)
        ids.add(track.id)
        for (id in ids.filter { it > 0 }) {
            try { catalog(id)?.takeIf { it.lines.isNotEmpty() }?.let { return it } }
            catch (_: Exception) { DangoLogger.warn("NetEase lyric stage=catalog request failed") }
        }
        return null
    }

    internal fun parse(root: JsonObject): Lyrics? {
        val code = root.number("code")
        DangoLogger.info("NetEase lyric stage=cloud code={}", code)
        if (code != 200L) return null
        val data = root.obj("data") ?: root
        fun text(value: JsonElement?, depth: Int = 0): String? {
            if (value == null || value.isJsonNull || depth > 3) return null
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) return value.asString.takeIf { it.isNotBlank() }
            if (!value.isJsonObject) return null
            for (key in listOf("lyric", "lrc", "data")) text(value.asJsonObject.get(key), depth + 1)?.let { return it }
            return null
        }
        val translated = text(data.get("tlyric"))?.let { LyricsProvider.parseLrc(it) } ?: emptyList()
        val candidates = listOf(data.get("yrc"), data.get("lrc"), data.get("lyric"), root.get("lrc"), root.get("lyric"))
        // 逐字歌词后半截跑偏时拿普通 LRC 修（见 LyricsProvider.repairYrc）
        val lrc by lazy { candidates.drop(1).firstNotNullOfOrNull { text(it) }?.let { LyricsProvider.parseLrc(it) } ?: emptyList() }
        for ((index, value) in candidates.withIndex()) {
            val raw = text(value) ?: continue
            val lines = if (index == 0) LyricsProvider.repairYrc(LyricsProvider.parseYrc(raw), lrc) else LyricsProvider.parseLrc(raw)
            val result = Lyrics(LyricsProvider.withTranslation(lines, translated), "NetEase")
            if (result.lines.isNotEmpty()) {
                DangoLogger.info("NetEase lyric stage=cloud usable=true")
                return result
            }
        }
        DangoLogger.info("NetEase lyric stage=cloud usable=false")
        return null
    }
}
