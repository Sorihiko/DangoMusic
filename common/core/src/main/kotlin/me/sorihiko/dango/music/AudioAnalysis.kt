package me.sorihiko.dango.music

import javazoom.jl.decoder.Bitstream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.sqrt

/** Local audio facts. No account, provider metadata, file extension or new decoder dependency. */
internal object AudioAnalysis {
    private fun checkActive(active: () -> Boolean) { if (!active()) throw CancellationException() }

    /** Read compressed stream timing without decoding the whole song into PCM. Zero means unknown. */
    fun duration(bytes: ByteArray, active: () -> Boolean): Long = try {
        checkActive(active)
        val measured = when (Mp3Probe.kind(bytes)) {
            "flac" -> {
                if (bytes.size < 42 || (bytes[4].toInt() and 127) != 0) 0L else {
                    val packed = ByteBuffer.wrap(bytes, 18, 8).long
                    val rate = packed ushr 44
                    val samples = packed and ((1L shl 36) - 1)
                    if (rate in 8000..192000 && samples > 0) samples * 1000 / rate else 0L
                }
            }
            "ogg-vorbis" -> vorbisDuration(bytes, active)
            "id3", "mpeg-like" -> {
                val stream = Bitstream(ByteArrayInputStream(bytes))
                try {
                    var ms = 0.0
                    while (true) {
                        checkActive(active)
                        val frame = stream.readFrame() ?: break
                        ms += frame.ms_per_frame().toDouble()
                        stream.closeFrame()
                    }
                    ms.toLong()
                } finally { stream.close() }
            }
            else -> 0L
        }
        measured.takeIf { it in 1..86_400_000L } ?: 0L
    } catch (e: CancellationException) { throw e } catch (_: Exception) { 0L }

    private fun vorbisDuration(bytes: ByteArray, active: () -> Boolean): Long {
        if (bytes.size < 27) return 0L
        var offset = 0
        var serial = 0
        var rate = 0L
        var last = -1L
        while (offset + 27 <= bytes.size) {
            checkActive(active)
            if (bytes[offset] != 79.toByte() || bytes[offset+1] != 103.toByte() ||
                bytes[offset+2] != 103.toByte() || bytes[offset+3] != 83.toByte() || bytes[offset+4] != 0.toByte()) return 0L
            val segments = bytes[offset+26].toInt() and 255
            val start = offset + 27 + segments
            if (start > bytes.size) return 0L
            var end = start
            for (i in 0 until segments) end += bytes[offset+27+i].toInt() and 255
            if (end > bytes.size) return 0L
            val pageSerial = ByteBuffer.wrap(bytes, offset+14, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (offset == 0) {
                if (end - start < 30 || bytes[start] != 1.toByte() || String(bytes, start+1, 6, Charsets.US_ASCII) != "vorbis") return 0L
                rate = ByteBuffer.wrap(bytes, start+12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong()
                if (rate !in 8000..192000) return 0L
                serial = pageSerial
            } else if (pageSerial != serial) return 0L // Do not guess timing for multiplexed/chained streams.
            val granule = ByteBuffer.wrap(bytes, offset+6, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
            if (granule >= 0) last = granule
            offset = end
        }
        return if (offset == bytes.size && last > 0 && last <= Long.MAX_VALUE / 1000) last * 1000 / rate else 0L
    }

    /** Fixed gain from at most the first 15 seconds. Called on the loader, never on the render/audio thread. */
    fun gain(bytes: ByteArray, active: () -> Boolean): Float? = try {
        checkActive(active)
        AudioDecoder.open(bytes).use { decoder ->
            val meter = Meter()
            var elapsed = 0f
            while (elapsed < 15_000f) {
                checkActive(active)
                val frame = decoder.read() ?: break
                val remaining = ((15_000f - elapsed) * frame.rate / 1000f).toInt().coerceAtLeast(0)
                val length = minOf(frame.length, remaining * frame.channels)
                if (length == 0) break
                val sample = AudioDecoder.Frame(frame.samples, length, frame.rate, frame.channels)
                meter.add(sample)
                elapsed += sample.durationMs
            }
            checkActive(active)
            meter.gain()
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /** Gated RMS approximation, not LUFS: silent introductions must not trigger large boosts. */
    class Meter {
        private val windows = ArrayList<Double>()
        private var energy = 0.0
        private var samples = 0
        private var frames = 0
        private var windowFrames = 0
        private var rate = 0
        private var channels = 0
        private var peak = 0.0

        fun add(frame: AudioDecoder.Frame) {
            require(frame.rate > 0 && frame.channels in 1..2 && frame.length in 0..frame.samples.size && frame.length % frame.channels == 0)
            if (rate != frame.rate || channels != frame.channels) {
                flush()
                rate = frame.rate; channels = frame.channels
                windowFrames = (rate / 5).coerceAtLeast(1)
            }
            var i = 0
            while (i < frame.length) {
                for (ch in 0 until channels) {
                    val amplitude = frame.samples[i++].toDouble() / 32768.0
                    energy += amplitude * amplitude
                    samples++
                    peak = maxOf(peak, kotlin.math.abs(amplitude))
                }
                if (++frames >= windowFrames) flush()
            }
        }
        private fun flush() {
            if (samples > 0) windows.add(energy / samples)
            energy = 0.0; samples = 0; frames = 0
        }
        fun gain(): Float? {
            flush()
            val audible = windows.filter { it > 0.00001 } // Ignore windows below -50 dBFS.
            if (audible.isEmpty()) return if (windows.isEmpty()) null else 1f
            val relativeGate = audible.average() * 0.1
            val gated = audible.filter { it >= relativeGate }
            val rms = sqrt(gated.average())
            val gain = minOf(0.125 / rms, if (peak > 0) 0.98 / peak else 1.0)
            return gain.coerceIn(0.05, 2.0).toFloat()
        }
    }
}
