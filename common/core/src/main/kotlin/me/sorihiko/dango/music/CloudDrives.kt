package me.sorihiko.dango.music

import me.sorihiko.dango.i18n.I18n

/** Fixed navigation entries; never confused with provider playlist IDs. */
object CloudDrives {
    const val QQ_ID = -163004L
    const val KUGOU_ID = -163005L
    fun id(source: MusicSource): Long = when (source) {
        MusicSource.NETEASE -> NeteaseApi.CLOUD_ID
        MusicSource.QQ -> QQ_ID
        MusicSource.KUGOU -> KUGOU_ID
    }
    fun entry(source: MusicSource): NeteasePlaylist? {
        val loggedIn = if (source == MusicSource.NETEASE) NeteaseApi.loggedIn else NativeAccounts.get(source) != null
        return if (loggedIn) NeteasePlaylist(id(source), I18n.text("Cloud drive"), 0, "", false) else null
    }
    fun note(source: MusicSource): String = when (source) {
        MusicSource.QQ -> "Only uploads matched to QQ Music's catalog are shown. Original unmatched files are not supported."
        MusicSource.KUGOU -> "Cloud uploads support MP3, Ogg Vorbis and FLAC. Private cloud tracks cannot be shared in Listen Together."
        else -> ""
    }
    fun privateTrack(track: NeteaseTrack): Boolean = track.source == MusicSource.KUGOU && track.cloud
}
