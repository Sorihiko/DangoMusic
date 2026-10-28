package me.sorihiko.dango.playback

import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.party.ListenParty
import me.sorihiko.dango.playback.salt.SaltSessionSource

/**
 * 播放会话路由：「内置播放 / Salt Player」两个模式的切换与读取入口。
 *
 * 切换保证：
 * - 不双重播放：进 Salt 前停掉内置播放；
 * - 不混数据：切回内置时停掉 Salt 轮询并清空展示快照；
 * - 一起听歌：房间中禁止进入 Salt（由调用方提示用户先退房），不自动解散房间。
 */
object PlaybackSessions {

    val builtin = BuiltinSessionSource()

    /** Salt 会话源：仅 Salt 模式下运行 */
    val salt = SaltSessionSource()

    @Volatile var mode: PlaybackMode = PlaybackMode.BUILT_IN
        private set

    fun active(): PlaybackSessionSource = when (mode) {
        PlaybackMode.BUILT_IN -> builtin
        PlaybackMode.SALT -> salt
    }

    enum class SwitchResult {
        /** 已切换（或本就是目标模式） */
        OK,
        /** 一起听歌房间中，拒绝进入 Salt——先退房 */
        IN_ROOM,
    }

    /**
     * 切换播放模式。
     * 进入 Salt 时停止内置播放并使旧加载失效；切回内置时停止 Salt 轮询并清空快照。
     */
    fun switchMode(target: PlaybackMode): SwitchResult {
        if (mode == target) return SwitchResult.OK
        if (target == PlaybackMode.SALT && ListenParty.role != ListenParty.Role.NONE) {
            return SwitchResult.IN_ROOM
        }
        when (target) {
            PlaybackMode.SALT -> {
                // 停止内置播放：旧加载任务随 generation 失效，不会有双重声音
                MusicPlayer.stop()
                builtin.invalidateLyrics()
                salt.start()
            }
            PlaybackMode.BUILT_IN -> {
                // 停止 Salt 轮询：迟到响应被 epoch 过滤，不会再写入展示
                salt.stop()
            }
        }
        mode = target
        return SwitchResult.OK
    }
}
