package me.sorihiko.dango.music

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Shared song model; historical class name is retained for existing consumers. */
class NeteaseTrack @JvmOverloads constructor(
    val id: Long,
    val name: String,
    val artists: List<String>,
    @Volatile var durationMs: Long,
    @Volatile var coverUrl: String,
    val source: MusicSource = MusicSource.NETEASE,
    val nativeId: String = id.toString(),
    val mediaId: String = nativeId,
    val albumId: String = "",
) {
    /** 酷狗目录的歌曲版本标识；不是专辑 ID，也不改变歌曲 hash。 */
    var albumAudioId: String = ""
    /** 云盘上传与普通曲库不能混用替代版本。 */
    var cloud: Boolean = false
    /** 当前账号云盘上传的标识；可能与 simpleSong 的曲库标识不同。 */
    var cloudId: Long = 0
    /** 歌手 ID（音源自己的，QQ 是 mid），和 [artists] 一一对应；拿不到的是 ""，点进歌手页时再按名字找 */
    var artistIds: List<String> = emptyList()
    val key: String get() = "${source.key}:$nativeId" + if (source == MusicSource.KUGOU && cloud) ":cloud" else ""
    companion object {
        /** 解析网易云接口返回的歌曲 JSON（新旧两种字段名都认：artists/ar、duration/dt、album/al） */
        fun parse(raw: JsonObject): NeteaseTrack? {
            val track = raw.get("simpleSong")?.takeIf { it.isJsonObject }?.asJsonObject ?: raw
            val id = sequenceOf(str(track, "id"), str(raw, "songId"), str(raw, "id")).mapNotNull { it.toLongOrNull()?.takeIf { value -> value > 0 } }.firstOrNull() ?: return null
            val name = str(track, "name").ifEmpty { str(raw, "songName").ifEmpty { str(raw, "fileName") } }
            val artistsEl: JsonElement? = track.get("artists") ?: track.get("ar")
            val artistPairs = if (artistsEl != null && artistsEl.isJsonArray) {
                artistsEl.asJsonArray.mapNotNull { a ->
                    if (!a.isJsonObject) return@mapNotNull null
                    val o = a.asJsonObject
                    str(o, "name").takeIf { it.isNotEmpty() }?.let { it to (str(o, "id").takeIf { id -> (id.toLongOrNull() ?: 0L) > 0 } ?: "") }
                }
            } else emptyList()
            val artists = artistPairs.map { it.first }
            val dur = (track.get("duration") ?: track.get("dt"))
                ?.let { if (it.isJsonNull) 0L else try { it.asLong } catch (_: Exception) { 0L } } ?: str(raw, "songLength").toLongOrNull() ?: 0L
            val album = track.get("album") ?: track.get("al")
            val cover = if (album != null && album.isJsonObject) {
                str(album.asJsonObject, "picUrl").ifEmpty { str(album.asJsonObject, "cover") }
            } else ""
            return NeteaseTrack(id, name, artists, dur, cover.ifEmpty { str(raw, "cover") }).apply {
                val type = str(track, "t").ifEmpty { str(raw, "t") }
                cloud = type == "1" || type == "2" || str(raw, "sourceType") == "cloud" || raw.has("simpleSong")
                if (cloud) cloudId = str(raw, "songId").toLongOrNull()?.takeIf { it > 0 } ?: id
                artistIds = artistPairs.map { it.second }
            }
        }

        private fun str(o: JsonObject, name: String): String {
            val e = o.get(name) ?: return ""
            return if (e.isJsonNull || !e.isJsonPrimitive) "" else e.asString
        }
    }
}
