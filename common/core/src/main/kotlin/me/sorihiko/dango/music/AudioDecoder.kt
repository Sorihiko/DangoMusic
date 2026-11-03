package me.sorihiko.dango.music

import java.io.ByteArrayInputStream
import java.io.Closeable
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import com.jcraft.jorbis.DangoVorbis

/** 分块解码，MP3、Vorbis 和 FLAC 共用后面的音量、鼓点、混响和音频输出。 */
internal interface AudioDecoder : Closeable {
    class Frame(val samples: ShortArray, val length: Int, val rate: Int, val channels: Int) {
        val durationMs: Float get() = length * 1000f / (rate * channels)
    }
    fun read(): Frame?
    fun skipTo(ms: Long, active: () -> Boolean): Float {
        var skipped = 0f
        while (skipped < ms && active()) skipped += read()?.durationMs ?: break
        return skipped
    }

    class Started(val decoder: AudioDecoder, val skippedMs: Float, val first: Frame?)

    companion object {
        /** A bad catalog duration must not turn a valid seek into an undecodable-file error. */
        fun start(bytes: ByteArray, startMs: Long, active: () -> Boolean): Started {
            var decoder = open(bytes)
            try {
                var skipped = decoder.skipTo(startMs, active)
                if (!active()) throw java.util.concurrent.CancellationException()
                var first = decoder.read()
                if (first == null && startMs > 0 && skipped > 0) {
                    decoder.close()
                    decoder = open(bytes)
                    skipped = decoder.skipTo((skipped.toLong() - 1000L).coerceAtLeast(0L), active)
                    if (!active()) throw java.util.concurrent.CancellationException()
                    first = decoder.read()
                }
                return Started(decoder, skipped, first)
            } catch (e: Throwable) {
                try { decoder.close() } catch (_: Exception) {}
                throw e
            }
        }

        fun open(bytes: ByteArray): AudioDecoder = when (Mp3Probe.kind(bytes)) {
            "flac" -> Flac(bytes)
            "ogg-vorbis" -> Vorbis(bytes)
            "ogg-opus" -> throw SourceAccessException("Ogg Opus audio is not supported yet.")
            "ogg" -> throw SourceAccessException("Unrecognized Ogg audio encoding.")
            else -> Mp3(bytes)
        }
    }
}

private class Mp3(bytes: ByteArray) : AudioDecoder {
    private val stream = Bitstream(ByteArrayInputStream(bytes))
    private val decoder = Decoder()
    override fun read(): AudioDecoder.Frame? {
        // 容忍少量损坏帧，但损坏数据不能无限占用解码线程。
        repeat(128) {
            val header = stream.readFrame() ?: return null
            try {
                val pcm = decoder.decodeFrame(header, stream) as SampleBuffer
                return AudioDecoder.Frame(pcm.buffer, pcm.bufferLength, header.frequency(), pcm.channelCount)
            } catch (_: Exception) { } finally { stream.closeFrame() }
        }
        throw IllegalStateException("Too many damaged audio frames")
    }
    override fun skipTo(ms: Long, active: () -> Boolean): Float {
        var skipped = 0f
        while (skipped < ms && active()) {
            val header = stream.readFrame() ?: break
            skipped += header.ms_per_frame()
            stream.closeFrame()
        }
        return skipped
    }
    override fun close() { stream.close() }
}

private class Vorbis(bytes: ByteArray) : AudioDecoder {
    private val stream = DangoVorbis(ByteArrayInputStream(bytes))
    private val buffer = ByteArray(8192)
    override fun read(): AudioDecoder.Frame? {
        val count = stream.readPcm(buffer)
        if (count == 0) return null
        check(count > 0) { "Damaged Ogg Vorbis audio" }
        val info = stream.getInfo(-1)
        check(info.rate in 8000..192000 && info.channels in 1..2 && count % (info.channels * 2) == 0) { "Unsupported Vorbis audio format" }
        val pcm = ShortArray(count / 2)
        for (i in pcm.indices) pcm[i] = ((buffer[i * 2].toInt() and 255) or (buffer[i * 2 + 1].toInt() shl 8)).toShort()
        return AudioDecoder.Frame(pcm, pcm.size, info.rate, info.channels)
    }
    override fun close() = stream.close()
}

/** 原生 FLAC 只解码当前帧，输出继续交给共用的音效和声卡链路。 */
private class Flac(bytes: ByteArray) : AudioDecoder {
    private val reader = FlacReader(bytes)
    override fun read(): AudioDecoder.Frame? = reader.readFrame()?.let { AudioDecoder.Frame(it.samples, it.samples.size, it.rate, it.channels) }
    override fun close() = reader.close()
}
