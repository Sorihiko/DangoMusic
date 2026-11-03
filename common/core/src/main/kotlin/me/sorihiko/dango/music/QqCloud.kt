package me.sorihiko.dango.music

import com.google.gson.JsonObject

/** Read-only cloud matches. Unmatched originals have no ordinary QQ playback MID. */
internal class QqCloud(
    private val call: (String, String, JsonObject, NativeAccount?) -> JsonObject = QqMusicSource::cgi,
) {
    fun tracks(account: NativeAccount): List<NeteaseTrack> {
        require(account.source == MusicSource.QQ)
        val output = LinkedHashMap<String, NeteaseTrack>()
        val pages = HashSet<String>()
        var received = 0
        for (page in 1..100) {
            val data = call("music.WeiyunMusic.WeiyunMusicRead", "GetMatchAudio", JsonObject().apply {
                addProperty("uin", account.userId); addProperty("file_id", "")
                addProperty("page_no", page); addProperty("page_size", 2000)
            }, account)
            val raw = data.get("info_list")
            if (!data.has("info_list") || (raw != null && !raw.isJsonNull && !raw.isJsonArray))
                throw SourceAccessException("QQ Music returned incomplete cloud data. Please retry.")
            val rows = if (raw?.isJsonArray == true) raw.asJsonArray else com.google.gson.JsonArray()
            if (rows.size() > 0 && !pages.add(KugouCloud.md5(rows.toString())))
                throw SourceAccessException("QQ Music repeated a cloud page. Please retry.")
            for (item in rows) {
                if (!item.isJsonObject) continue
                val row = item.asJsonObject
                if (row.number("matched") == 2L) continue
                val song = row.obj("song_info") ?: continue
                QqMusicSource.parseTrack(song)?.let { if (!output.containsKey(it.key)) output[it.key] = it }
            }
            received += rows.size()
            TrackLoadProgress.report(received, data.number("total").coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            if (TrackLoadProgress.wantsPartial) TrackLoadProgress.partial(output.values.toList())
            if (data.number("has_more") != 1L) return output.values.toList()
            if (rows.size() == 0) throw SourceAccessException("QQ Music returned incomplete cloud data. Please retry.")
        }
        throw SourceAccessException("QQ Music returned too many cloud pages. Please retry.")
    }
}
