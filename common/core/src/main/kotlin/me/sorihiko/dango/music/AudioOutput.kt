package me.sorihiko.dango.music

import java.io.File
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

/**
 * 播放器往外送声音的地方（16 位有符号、小端的 PCM）。
 * 电脑上是 Java Sound（[JavaSoundOutput]）；安卓等 Java 不带声音库的环境由平台层给一个 OpenAL 的。
 */
interface AudioOutput {
    /** 声卡实际已经放出去的时长（微秒），从打开时算起 */
    val positionMicros: Long

    /** 正在出声（没有暂停） */
    val isRunning: Boolean

    fun start()
    fun stop()

    /** 送一段数据进去；缓冲满了就等着，直到放出去一些 */
    fun write(data: ByteArray, offset: Int, length: Int)

    /** 等已经送进去的数据全部放完 */
    fun drain()

    fun flush()
    fun close()

    /** 音量 0..1（线性振幅） */
    fun setVolume(volume: Float)

    /** 输出没了（游戏重启了声音系统：F3+T、换了耳机 / 输出设备），要重新打开一个接着放 */
    val lost: Boolean get() = false

    companion object {
        /**
         * 这个 Java 自己带不带声音库（jsound）。安卓的 Java 不带：这时用 Java Sound 要靠启动器的转接，
         * 有的启动器转接到 OpenAL 时会抢走游戏的声音环境，游戏音效全部出错、变成杂音，所以改用平台层的输出。
         */
        val javaSoundNative: Boolean by lazy {
            val home = File(System.getProperty("java.home") ?: return@lazy true)
            listOf("bin/jsound.dll", "lib/libjsound.so", "lib/libjsound.dylib", "lib/amd64/libjsound.so", "lib/aarch64/libjsound.so")
                .any { File(home, it).isFile }
        }
    }
}

/**
 * 电脑上默认的输出：Java Sound 的声卡线路。
 *
 * 线路绑在打开时的那个设备上：放到一半换了默认设备（连上蓝牙耳机等），旧线路不再往外放，
 * 缓冲一直是满的，直接调 line.write 会永远等下去，暂停 / 播放也救不回来（#55）。
 * 所以自己等缓冲有空位：开着的时候播放位置超过 [STALL_MS] 没动，就当输出没了（[lost]），让播放器换一条新线路。
 */
class JavaSoundOutput(private val line: SourceDataLine) : AudioOutput {
    @Volatile private var softwareVolume = 1f
    private var scaled = ByteArray(0)
    override val positionMicros: Long get() = line.microsecondPosition
    override val isRunning: Boolean get() = line.isRunning

    /** 我们让它放着（start 了、没 stop）。线路自己的 isRunning 在设备没了时不一定可靠 */
    @Volatile private var started = false
    @Volatile private var dead = false
    override val lost: Boolean get() = dead

    /** 上次看到播放位置往前走的时间，和那时的位置 */
    private var lastFrame = -1L
    private var lastMoved = 0L

    override fun start() {
        lastMoved = System.currentTimeMillis()
        started = true
        line.start()
    }

    override fun stop() {
        started = false
        line.stop()
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        val volume = softwareVolume
        if (volume == 1f) {
            writeAll(data, offset, length)
        } else {
            require(length % 2 == 0 && offset >= 0 && length >= 0 && offset <= data.size - length)
            if (scaled.size < length) scaled = ByteArray(length)
            var i = 0
            while (i < length) {
                val sample = ((data[offset+i].toInt() and 255) or (data[offset+i+1].toInt() shl 8)).toShort().toInt()
                val adjusted = (sample * volume).toInt().coerceIn(-32768, 32767)
                scaled[i] = adjusted.toByte(); scaled[i+1] = (adjusted shr 8).toByte()
                i += 2
            }
            writeAll(scaled, 0, length)
        }
    }

    /** 有多少空位写多少；没空位就等，期间检查声卡是不是还在放 */
    private fun writeAll(data: ByteArray, offset: Int, length: Int) {
        var off = offset
        var left = length
        val frame = line.format.frameSize.coerceAtLeast(1)
        while (left > 0) {
            if (!line.isOpen) return   // 被关掉了（切歌、停止）：和原来一样直接返回
            val room = line.available() / frame * frame
            if (room > 0) {
                val n = line.write(data, off, minOf(left, room))
                off += n
                left -= n
                if (n > 0) continue
            }
            checkStall()
            Thread.sleep(5)
        }
    }

    /** 开着的时候播放位置太久没动：输出没了 */
    private fun checkStall() {
        val now = System.currentTimeMillis()
        val pos = line.longFramePosition
        if (!started || pos != lastFrame) {
            lastFrame = pos
            lastMoved = now
            return
        }
        if (now - lastMoved > STALL_MS) {
            dead = true
            throw IllegalStateException("Audio device stopped playing")
        }
    }

    override fun drain() {
        // 等缓冲放空（同样检查卡住），剩下的交给 line.drain
        while (line.isOpen && line.available() < line.bufferSize) {
            checkStall()
            Thread.sleep(10)
        }
        if (line.isOpen) line.drain()
    }
    override fun flush() = line.flush()
    override fun close() = line.close()

    override fun setVolume(volume: Float) {
        val requested = if (volume.isFinite()) volume.coerceIn(0f, 1f) else 0f
        try {
            val gain = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val db = if (requested <= 0.0001f) gain.minimum else (20.0 * log10(requested.toDouble())).toFloat()
            gain.value = db.coerceIn(gain.minimum, gain.maximum)
            softwareVolume = 1f
        } catch (_: Exception) {
            // Some formats/devices expose no gain control. The user's volume must still apply.
            softwareVolume = requested
        }
    }

    private companion object {
        /** 播放位置这么久没动就当设备没了（正常缓冲约 0.25 秒，偶尔卡一下也远小于这个） */
        const val STALL_MS = 2000L
    }
}
