package me.sorihiko.dango.playback

import me.sorihiko.dango.lyrics.Lyrics

/**
 * 播放接入模式：音频由谁播放。
 * 与「网易云 / QQ / 酷狗」的曲库选择正交——那只是内置模式下的曲库维度。
 */
enum class PlaybackMode { BUILT_IN, SALT }

/**
 * 播放状态。与 [SessionLink] 严格区分：
 * 连接失败不是暂停；[UNKNOWN] 表示尚未收到状态事件，不是明确的停止。
 */
enum class SessionState {
    /** 没有曲目，或已停止 */
    NONE,
    LOADING,
    PLAYING,
    PAUSED,
    /** 播放自然结束 */
    ENDED,
    /** 出错（内置加载失败等） */
    ERROR,
    /** 尚未收到状态事件（Salt 启用后可能出现） */
    UNKNOWN,
}

/** 会话数据源自身的可用状态；不能与播放状态混用 */
enum class SessionLink {
    /** 正常 */
    READY,
    /** 连接建立中（Salt 探活阶段） */
    CONNECTING,
    /** 未连接或连接断开——不等于暂停 */
    DISCONNECTED,
}

/**
 * 歌词可用状态。
 * [UNAVAILABLE] 表示当前拿不到歌词，不代表「这首歌没有歌词」，不可永久缓存结论。
 */
enum class LyricsStatus {
    /** 尚未查询 */
    UNKNOWN,
    LOADING,
    AVAILABLE,
    UNAVAILABLE,
}

/**
 * 展示用曲目信息，与播放来源无关。
 * [key] 是显示与缓存身份：内置为 `netease:123` 一类，Salt 为 `salt:实例:代次`。
 */
class SessionTrack(
    val key: String,
    val title: String,
    val artists: List<String>,
    val durationMs: Long,
    /** 公共封面 URL；需要认证的本机封面走字节入口。 */
    val coverUrl: String = "",
    /** 仅在后台线程调用，令牌不进入 URL 或展示模型。 */
    val coverBytes: (() -> ByteArray?)? = null,
) {
    val artistText: String get() = artists.joinToString(" / ")
}

/** 当前播放会话的只读快照 */
class PlaybackSnapshot(
    val link: SessionLink,
    /** null = 当前无曲目 */
    val track: SessionTrack?,
    val state: SessionState,
    /** 当前显示位置（毫秒）；内置为精确值，Salt 由适配器外推后给出 */
    val positionMs: Long,
    val lyricsStatus: LyricsStatus,
    /** 歌词版本：正文变化的标识，重新取正文的依据。内置恒为 1 */
    val lyricsVersion: Long,
) {
    val playing: Boolean get() = state == SessionState.PLAYING
}

/**
 * 「当前播放会话」读取接口：曲目信息、显示身份、状态、当前位置、歌词及其版本。
 *
 * 约束：
 * - [snapshot] / [lyrics] 必须非阻塞，可在渲染线程调用；
 * - 歌词正文的加载经 [requestLyrics] 异步进行，调用方按 [PlaybackSnapshot.lyricsVersion] 决定是否重取；
 */
interface PlaybackSessionSource {
    fun snapshot(): PlaybackSnapshot

    /** 歌词正文；未就绪返回 null。与 [PlaybackSnapshot.lyricsVersion] 对应 */
    fun lyrics(): Lyrics?

    /** 幂等、非阻塞地触发歌词加载 */
    fun requestLyrics()
}
