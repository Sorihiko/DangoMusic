package me.sorihiko.dango.music


/** 与正式播放共用解码器探测，不能仅凭 URL 后缀或服务器成功状态判断格式。 */
internal object Mp3Probe {
    fun decodable(bytes: ByteArray): Boolean {
        return try { AudioDecoder.open(bytes).use { it.read()?.length?.let { length -> length > 0 } == true } } catch (_: Exception) { false }
    }

    fun kind(bytes: ByteArray): String {
        fun tag(offset: Int, text: String) = bytes.size >= offset + text.length && text.indices.all { bytes[offset + it].toInt() and 255 == text[it].toInt() }
        return when {
            tag(0, "fLaC") -> "flac"
            tag(0, "OggS") -> {
                // 第一个 Ogg 页的段表之后才是识别包，不能只凭 OggS 推断编码。
                val packet = if (bytes.size > 26) 27 + (bytes[26].toInt() and 255) else bytes.size
                when {
                    tag(packet, "\u0001vorbis") -> "ogg-vorbis"
                    tag(packet, "OpusHead") -> "ogg-opus"
                    else -> "ogg"
                }
            }
            tag(4, "ftyp") -> "mp4"
            tag(0, "RIFF") -> "riff"
            tag(0, "ID3") -> "id3"
            bytes.firstOrNull()?.toInt()?.and(255) == 255 -> "mpeg-like"
            else -> "unknown"
        }
    }
}
