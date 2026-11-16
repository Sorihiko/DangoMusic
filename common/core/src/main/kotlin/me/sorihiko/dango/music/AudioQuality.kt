package me.sorihiko.dango.music

/**
 * 音质档位。设置里选所有歌的默认档；播放栏上可以给正在放的这首临时换一档（换歌就回到默认）。
 * 要的档位拿不到（没会员、没有这个文件）时，各音源自动往下一档降，不会放不出来。
 */
enum class AudioQuality(val label: String) {
    STANDARD("Standard quality"), HIGH("High quality"), LOSSLESS("Lossless quality");

    /** 网易云 v1 接口的 level，从高到低 */
    val neteaseLevels: List<String> get() = when (this) {
        LOSSLESS -> listOf("lossless", "exhigh", "standard")
        HIGH -> listOf("exhigh", "standard")
        STANDARD -> listOf("standard")
    }

    /** QQ 音乐的文件（前缀, 扩展名），从高到低：F000 = FLAC，M800 = 320k，M500 = 128k */
    val qqFiles: List<Pair<String, String>> get() = when (this) {
        LOSSLESS -> listOf("F000" to "flac", "M800" to "mp3", "M500" to "mp3")
        HIGH -> listOf("M800" to "mp3", "M500" to "mp3")
        STANDARD -> listOf("M500" to "mp3")
    }

    /** 酷狗的 quality 参数，从高到低 */
    val kugouQualities: List<String> get() = when (this) {
        LOSSLESS -> listOf("flac", "320", "128")
        HIGH -> listOf("320", "128")
        STANDARD -> listOf("128")
    }
}

object PlaybackQuality {
    /** 设置里的默认音质 */
    @Volatile var default = AudioQuality.HIGH
    /** 播放栏上给当前这首临时选的；换歌时清掉 */
    @Volatile var override: AudioQuality? = null
    val wanted: AudioQuality get() = override ?: default

    /**
     * 实际拿到的音质（显示用）：FLAC 就是无损；MP3 按文件大小和时长估码率，
     * 接近 320 / 192 / 128 的就显示成那一档
     */
    fun describe(bytes: ByteArray, durationMs: Long): String {
        if (Mp3Probe.kind(bytes) == "flac") return "FLAC"
        if (durationMs <= 0) return ""
        val kbps = bytes.size * 8.0 / durationMs
        return when {
            kbps >= 280 -> "320K"
            kbps >= 170 -> "192K"
            kbps >= 100 -> "128K"
            else -> "${Math.round(kbps)}K"
        }
    }
}
