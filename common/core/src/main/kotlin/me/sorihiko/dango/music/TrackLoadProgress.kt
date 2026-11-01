package me.sorihiko.dango.music

/**
 * 歌单歌曲的加载进度：各音源分页取歌时在这里报告"已经拿到几首、一共几首"（不知道总数时为 0），
 * 界面据此画进度条；还可以用 [partial] 把已经拿到的那部分歌先交出去，界面先显示，用户不用等整张歌单。
 * 只对用 [track] 包起来的那个线程生效，别的线程报告了也没人收。
 */
object TrackLoadProgress {
    private val listener = ThreadLocal<((Int, Int) -> Unit)?>()
    private val partialListener = ThreadLocal<((List<NeteaseTrack>) -> Unit)?>()

    fun <T> track(onProgress: (Int, Int) -> Unit, block: () -> T): T = track(onProgress, null, block)

    fun <T> track(onProgress: (Int, Int) -> Unit, onPartial: ((List<NeteaseTrack>) -> Unit)?, block: () -> T): T {
        listener.set(onProgress)
        partialListener.set(onPartial)
        try {
            return block()
        } finally {
            listener.remove()
            partialListener.remove()
        }
    }

    @JvmStatic
    fun report(loaded: Int, total: Int) {
        try {
            listener.get()?.invoke(loaded, total)
        } catch (_: Exception) {
        }
    }

    /**
     * 已经拿到的歌（按最终顺序排好的开头那一段）。之后每次交出的都必须以上一次的为开头、只往后加，
     * 这样列表只会变长，已经显示的歌不会挪位置，正在放的队列也能直接换成更长的版本。
     */
    @JvmStatic
    fun partial(tracks: List<NeteaseTrack>) {
        try {
            partialListener.get()?.invoke(tracks)
        } catch (_: Exception) {
        }
    }

    /** 有没有人在收 [partial]（没人收就不用费劲去拼列表） */
    val wantsPartial: Boolean get() = partialListener.get() != null
}
