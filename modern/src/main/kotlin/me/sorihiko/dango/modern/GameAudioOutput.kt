package me.sorihiko.dango.modern

import me.sorihiko.dango.music.AudioOutput
import org.lwjgl.openal.AL10
import org.lwjgl.openal.AL11
import org.lwjgl.openal.ALC10
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * 用游戏自己的 OpenAL 放歌（安卓的 Java 没有 Java Sound 用的声音库时）。
 *
 * 直接用游戏已经打开的声音环境，只建一个自己的音源和几块缓冲，**不切换当前环境**：
 * 安卓启动器自带的转接（把 Java Sound 转到 OpenAL）会另建环境并切过去，游戏音效就全部出错、变成杂音。
 *
 * 数据攒成约 50 ms 一块排队给音源，最多 [BUFFERS] 块（约 0.25 秒）；排满了就等最早一块放完。
 */
class GameAudioOutput private constructor(
    private val context: Long,
    private val sampleRate: Int,
    private val channels: Int,
) : AudioOutput {

    private val format = if (channels >= 2) AL10.AL_FORMAT_STEREO16 else AL10.AL_FORMAT_MONO16
    private val frameBytes = 2 * channels.coerceIn(1, 2)
    private val chunkBytes = (sampleRate / 20).coerceAtLeast(256) * frameBytes
    private val lock = Object()

    private val source = AL10.alGenSources()
    private val freeBuffers = ArrayDeque<Int>()
    /** 排在音源里的每一块有多少帧（和音源里的顺序一样） */
    private val queuedFrames = ArrayDeque<Int>()
    private var createdBuffers = 0
    /** 已经放完、从音源里取出来的帧数 */
    private var playedFrames = 0L

    private val pending: ByteBuffer = MemoryUtil.memAlloc(chunkBytes)

    @Volatile private var running = false
    @Volatile private var closed = false
    /** 声音系统重启前最后一次读到的播放位置 */
    private var lastPositionMicros = 0L

    init {
        check(AL10.alGetError() == AL10.AL_NO_ERROR) { "OpenAL source unavailable" }
    }

    override val positionMicros: Long
        get() = synchronized(lock) {
            if (closed) return 0L
            if (!alive()) return lastPositionMicros
            val offset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET).toLong()
            ((playedFrames + offset) * 1_000_000L / sampleRate).also { lastPositionMicros = it }
        }

    override val lost: Boolean get() = !closed && !alive()

    private fun alive() = AlContext.matches(context)

    override val isRunning: Boolean get() = running

    override fun start() = synchronized(lock) {
        running = true
        if (!closed && alive() && queuedFrames.isNotEmpty()) AL10.alSourcePlay(source)
    }

    override fun stop() = synchronized(lock) {
        running = false
        if (!closed && alive()) AL10.alSourcePause(source)
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        var off = offset
        var left = length - length % frameBytes
        while (left > 0) {
            // 切歌 / 拖进度时旧的解码线程还会写一下：已经关了就直接丢掉（和 Java Sound 一样）
            if (closed) return
            val n = minOf(left, pending.remaining())
            pending.put(data, off, n)
            off += n
            left -= n
            if (!pending.hasRemaining()) submit()
        }
    }

    /** 把攒好的一块交给音源；缓冲都在用就等最早一块放完 */
    private fun submit() {
        val buffer = takeBuffer() ?: return
        synchronized(lock) {
            if (closed) return
            checkContext()
            pending.flip()
            val frames = pending.remaining() / frameBytes
            AL10.alBufferData(buffer, format, pending, sampleRate)
            pending.clear()
            AL10.alSourceQueueBuffers(source, buffer)
            queuedFrames.addLast(frames)
            // 刚开始或者断过流（数据没跟上）：音源停了就重新开始放
            if (running && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) AL10.alSourcePlay(source)
            check(AL10.alGetError() == AL10.AL_NO_ERROR) { "OpenAL playback error" }
        }
    }

    private fun takeBuffer(): Int? {
        while (true) {
            synchronized(lock) {
                if (closed) return null
                checkContext()
                reclaim()
                freeBuffers.removeFirstOrNull()?.let { return it }
                if (createdBuffers < BUFFERS) {
                    createdBuffers++
                    return AL10.alGenBuffers()
                }
            }
            Thread.sleep(5)
        }
    }

    /** 放完的缓冲取回来，记到已经放出去的时长里 */
    private fun reclaim() {
        var done = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)
        while (done-- > 0) {
            freeBuffers.addLast(AL10.alSourceUnqueueBuffers(source))
            playedFrames += queuedFrames.removeFirstOrNull() ?: 0
        }
    }

    /** 游戏重启了声音系统（F3+T、换了输出设备）：原来的音源已经没了，抛出去让播放器换一个新的输出接着放 */
    private fun checkContext() {
        check(AlContext.matches(context)) { "Game audio was restarted" }
    }

    override fun drain() {
        if (pending.position() > 0) {
            // 最后不满一块的也送进去
            submit()
        }
        while (true) {
            synchronized(lock) {
                if (closed) return
                checkContext()
                reclaim()
                if (queuedFrames.isEmpty()) return
                if (running && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) AL10.alSourcePlay(source)
            }
            Thread.sleep(10)
        }
    }

    override fun flush() {
        synchronized(lock) {
            if (closed) return
            pending.clear()
            if (!alive()) return
            AL10.alSourceStop(source)
            reclaim()
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        running = false
        try {
            if (AlContext.matches(context)) {
                // 停下以后排着的缓冲都算放完了：全部取回来，和音源一起删掉
                AL10.alSourceStop(source)
                reclaim()
                AL10.alSourcei(source, AL10.AL_BUFFER, 0)
                AL10.alDeleteSources(source)
                for (b in freeBuffers) AL10.alDeleteBuffers(b)
                freeBuffers.clear()
                AL10.alGetError()
            }
        } finally {
            MemoryUtil.memFree(pending)
        }
    }

    override fun setVolume(volume: Float) = synchronized(lock) {
        if (!closed && alive()) AL10.alSourcef(source, AL10.AL_GAIN, volume.coerceIn(0f, 1f))
    }

    companion object {
        private const val BUFFERS = 5

        /** 游戏的声音系统开着就能用；没开（声音设备打不开等）返回 null */
        fun open(sampleRate: Int, channels: Int): GameAudioOutput? {
            val context = AlContext.current()
            if (context == 0L || sampleRate <= 0 || channels <= 0) return null
            AL10.alGetError()
            return GameAudioOutput(context, sampleRate, channels).apply {
                AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
                AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f)
                AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0f)
            }
        }
    }
}

/**
 * 读游戏当前的 OpenAL 环境。
 * 有的安卓启动器（紫水晶）自带的 LWJGL 里没有 `ALC10.alcGetCurrentContext`（#47）：
 * 先直接按名字找这个函数调用；还找不到就当"不知道"，照样放歌，只是认不出声音系统重启。
 */
private object AlContext {
    /** 读不到当前环境时用的占位值 */
    private const val UNKNOWN = -1L

    @Volatile private var missing = false
    private val address: Long by lazy {
        try {
            org.lwjgl.openal.ALC.getFunctionProvider()?.getFunctionAddress("alcGetCurrentContext") ?: 0L
        } catch (e: LinkageError) {
            0L
        }
    }

    fun current(): Long {
        if (!missing) {
            try {
                return ALC10.alcGetCurrentContext()
            } catch (e: LinkageError) {
                missing = true
                me.sorihiko.dango.DangoLogger.warn("Dango Music: alcGetCurrentContext missing, looking it up directly")
            }
        }
        val fn = address
        if (fn != 0L) {
            try {
                return org.lwjgl.system.JNI.invokeP(fn)
            } catch (e: LinkageError) {
            }
        }
        return UNKNOWN
    }

    /** 还是 [context] 这个环境（读不到时一律当作还在） */
    fun matches(context: Long): Boolean {
        if (context == UNKNOWN) return true
        val now = current()
        return now == UNKNOWN || now == context
    }
}
