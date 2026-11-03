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

/** 电脑上默认的输出：Java Sound 的声卡线路 */
class JavaSoundOutput(private val line: SourceDataLine) : AudioOutput {
    @Volatile private var softwareVolume = 1f
    private var scaled = ByteArray(0)
    override val positionMicros: Long get() = line.microsecondPosition
    override val isRunning: Boolean get() = line.isRunning

    override fun start() = line.start()
    override fun stop() = line.stop()
    override fun write(data: ByteArray, offset: Int, length: Int) {
        val volume = softwareVolume
        if (volume == 1f) {
            line.write(data, offset, length)
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
            line.write(scaled, 0, length)
        }
    }
    override fun drain() = line.drain()
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
}
