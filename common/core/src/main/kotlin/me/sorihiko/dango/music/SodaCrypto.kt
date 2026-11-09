package me.sorihiko.dango.music

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 汽水音乐播放流：spade_a -> AES key，MP4（senc / 每样本 AES-128-CTR）-> 标准 FLAC。
 * 只在内存里处理，不落盘；输入上限和播放器下载上限一致。
 */
internal object SodaCrypto {
    private const val MAX_BYTES = 96 * 1024 * 1024
    private val HEX = Regex("[a-f0-9]{32}", RegexOption.IGNORE_CASE)

    private class Box(val offset: Int, val size: Int, val header: Int) {
        val dataStart: Int get() = offset + header
        val dataEnd: Int get() = offset + size
    }

    /** spade_a（base64）-> 32 位 hex 的 AES-128 key；失败返回空串。 */
    fun spadeKey(spadeA: String): String {
        val bytes = try {
            java.util.Base64.getDecoder().decode(spadeA.trim())
        } catch (_: IllegalArgumentException) {
            return ""
        }
        if (bytes.size < 3) return ""
        val padding = ((bytes[0].toInt() and 255) xor (bytes[1].toInt() and 255) xor (bytes[2].toInt() and 255)) - 48
        if (padding < 0 || bytes.size < padding + 2) return ""
        val inner = bytes.copyOfRange(1, bytes.size - padding)
        val out = ByteArray(inner.size)
        for (i in out.indices) {
            var value = ((inner[i].toInt() and 255) xor (if (i == 0) 0xFA else if (i == 1) 0x55 else inner[i - 2].toInt() and 255)) - bitCount(i) - 21
            while (value < 0) value += 0xFF
            out[i] = value.toByte()
        }
        if (out.isEmpty()) return ""
        val skip = base36(out[0].toInt() and 255)
        val length = bytes.size - padding - 2
        val end = 1 + length - skip
        if (skip > 64 || end > out.size || end < 1) return ""
        return String(out, 1, end - 1, Charsets.UTF_8)
    }

    /** 把加密的 MP4 还原成可以直接交给 FlacReader 的标准 FLAC。 */
    fun flacFromMp4(mp4: ByteArray, spadeA: String): ByteArray = flacFromMp4WithKey(mp4, spadeKey(spadeA))

    fun flacFromMp4WithKey(mp4: ByteArray, keyHex: String): ByteArray {
        require(mp4.size in 1..MAX_BYTES) { "Audio stream is too large" }
        require(HEX.matches(keyHex)) { "Invalid audio key" }
        val key = hexBytes(keyHex)

        val moov = findBox(mp4, "moov") ?: throw SourceAccessException("Audio container is missing moov")
        val trak = findBox(mp4, "trak", moov.dataStart, moov.dataEnd) ?: throw SourceAccessException("Audio container is missing trak")
        val mdia = findBox(mp4, "mdia", trak.dataStart, trak.dataEnd) ?: throw SourceAccessException("Audio container is missing mdia")
        val minf = findBox(mp4, "minf", mdia.dataStart, mdia.dataEnd) ?: throw SourceAccessException("Audio container is missing minf")
        val stbl = findBox(mp4, "stbl", minf.dataStart, minf.dataEnd) ?: throw SourceAccessException("Audio container is missing stbl")
        val stsd = findBox(mp4, "stsd", stbl.dataStart, stbl.dataEnd) ?: throw SourceAccessException("Audio container is missing stsd")
        val stsz = findBox(mp4, "stsz", stbl.dataStart, stbl.dataEnd) ?: throw SourceAccessException("Audio container is missing stsz")
        val senc = findBox(mp4, "senc", stbl.dataStart, stbl.dataEnd) ?: findBox(mp4, "senc", moov.dataStart, moov.dataEnd)
            ?: throw SourceAccessException("Audio container is missing encryption info")
        val mdat = findBox(mp4, "mdat") ?: throw SourceAccessException("Audio container is missing mdat")

        val dfLa = indexOf(mp4, "dfLa", stsd.dataStart, stsd.dataEnd)
        require(dfLa >= 4) { "Audio container is not FLAC" }
        val dfLaSize = u32(mp4, dfLa - 4).toInt()
        require(dfLaSize >= 8 && dfLa - 4 + dfLaSize <= stsd.dataEnd) { "Invalid FLAC metadata" }
        val metadata = mp4.copyOfRange(dfLa + 4, dfLa - 4 + dfLaSize)

        val fixed = u32(mp4, stsz.dataStart + 4)
        val count = u32(mp4, stsz.dataStart + 8)
        require(count in 1..10_000_000) { "Invalid audio sample table" }
        val ivs = u32(mp4, senc.dataStart + 4)
        require(ivs == count) { "Audio sample table mismatch" }

        val output = ByteArrayOutputStream(mp4.size)
        output.write(byteArrayOf(0x66, 0x4C, 0x61, 0x43))
        output.write(metadata, 4, metadata.size - 4)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        var offset = mdat.dataStart
        for (i in 0 until count.toInt()) {
            val size = if (fixed != 0L) fixed.toInt() else u32(mp4, stsz.dataStart + 12 + i * 4).toInt()
            require(size >= 0 && offset + size <= mp4.size) { "Invalid audio sample" }
            val iv = ByteArray(16)
            System.arraycopy(mp4, senc.dataStart + 8 + i * 8, iv, 0, 8)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            output.write(cipher.doFinal(mp4, offset, size))
            offset += size
        }
        return output.toByteArray()
    }

    private fun bitCount(value: Int): Int {
        var v = value - ((value ushr 1) and 0x55555555)
        v = (v and 0x33333333) + ((v ushr 2) and 0x33333333)
        return (((v + (v ushr 4)) and 0xF0F0F0F) * 0x1010101) ushr 24
    }

    private fun base36(value: Int): Int = when {
        value in 48..57 -> value - 48
        value in 97..122 -> value - 97 + 10
        else -> 0xFF
    }

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= data.size) { "Truncated audio container" }
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    private fun u64(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 8 <= data.size) { "Truncated audio container" }
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value
    }

    private fun findBox(data: ByteArray, type: String, start: Int = 0, end: Int = data.size): Box? {
        var position = start
        while (position + 8 <= end) {
            var size = u32(data, position)
            var header = 8
            if (size == 1L) {
                if (position + 16 > end) return null
                size = u64(data, position + 8)
                header = 16
            }
            if (size == 0L) size = (end - position).toLong()
            if (size < header || size > end - position) return null
            if (String(data, position + 4, 4, Charsets.ISO_8859_1) == type) return Box(position, size.toInt(), header)
            position += size.toInt()
        }
        return null
    }

    private fun indexOf(data: ByteArray, text: String, start: Int, end: Int): Int {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        var position = start
        while (position + bytes.size <= end) {
            var match = true
            for (i in bytes.indices) if (data[position + i] != bytes[i]) { match = false; break }
            if (match) return position
            position++
        }
        return -1
    }
}