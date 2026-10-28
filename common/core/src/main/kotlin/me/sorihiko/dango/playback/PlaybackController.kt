package me.sorihiko.dango.playback

import me.sorihiko.dango.music.MusicPlayer

/**
 * 播放控制统一入口：按当前模式路由到内置播放器或 Salt Player。
 *
 * Mod 是控制面板——歌在哪、放什么由引擎决定：
 * - 内置模式：直接驱动 MusicPlayer；
 * - Salt 模式：发 /control 命令，最终状态以 status 轮询为准（202 只是入队）。
 */
object PlaybackController {

    fun togglePause() {
        when (PlaybackSessions.mode) {
            PlaybackMode.BUILT_IN -> MusicPlayer.togglePause()
            PlaybackMode.SALT -> PlaybackSessions.salt.togglePause()
        }
    }

    fun next() {
        when (PlaybackSessions.mode) {
            PlaybackMode.BUILT_IN -> MusicPlayer.next()
            PlaybackMode.SALT -> PlaybackSessions.salt.next()
        }
    }

    fun previous() {
        when (PlaybackSessions.mode) {
            PlaybackMode.BUILT_IN -> MusicPlayer.prev()
            PlaybackMode.SALT -> PlaybackSessions.salt.previous()
        }
    }

    fun seekTo(positionMs: Long) {
        when (PlaybackSessions.mode) {
            PlaybackMode.BUILT_IN -> MusicPlayer.seek(positionMs)
            PlaybackMode.SALT -> PlaybackSessions.salt.seekTo(positionMs)
        }
    }
}
