package me.sorihiko.dango.music;

/** 按 RFC 9639 独立实现原生 FLAC 的逐帧解码；不缓存整首 PCM，不读取外部元数据资源。 */
public final class FlacReader implements java.io.Closeable {
    public static final class Block {
        public final short[] samples;
        public final int rate, channels;
        Block(short[] samples, int rate, int channels) { this.samples = samples; this.rate = rate; this.channels = channels; }
    }
    private static final int[] CRC8 = crcTable(8, 0x07), CRC16 = crcTable(16, 0x8005);
    private final Bits input;
    private int rate, channels, depth, maximumBlock, blocking = -1;
    private long total, decoded, frames;
    private long[][] samples;
    private boolean closed, ended;
    private byte[] expectedHash, hashBuffer;
    private java.security.MessageDigest digest;

    public FlacReader(byte[] bytes) {
        require(bytes.length <= 64 * 1024 * 1024, "FLAC input exceeds download limit");
        input = new Bits(bytes);
        require(input.read(32) == 0x664c6143L, "Invalid FLAC signature");
        boolean last = false, first = true;
        while (!last) {
            last = input.read(1) != 0;
            int type = (int) input.read(7), length = (int) input.read(24);
            require(type != 127 && (!first || type == 0), "Invalid FLAC metadata");
            int end = input.bytePosition() + length;
            require(end >= input.bytePosition() && end <= bytes.length, "Truncated FLAC metadata");
            if (type == 0) {
                require(first && length == 34, "Invalid FLAC stream info");
                int minimum = (int) input.read(16);
                maximumBlock = (int) input.read(16);
                require(minimum >= 16 && maximumBlock >= minimum, "Invalid FLAC block limits");
                input.read(24); input.read(24);
                rate = (int) input.read(20);
                channels = (int) input.read(3) + 1;
                depth = (int) input.read(5) + 1;
                total = input.read(36);
                expectedHash = new byte[16];
                boolean knownHash = false;
                for (int i = 0; i < 16; i++) { expectedHash[i] = (byte) input.read(8); knownHash |= expectedHash[i] != 0; }
                if (knownHash) {
                    try { digest = java.security.MessageDigest.getInstance("MD5"); }
                    catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("MD5 unavailable", e); }
                }
                require(rate > 0 && rate <= 192000 && depth >= 4 && depth <= 32, "Unsupported FLAC sample format");
                require(channels <= 2, "FLAC supports only mono or stereo in this player");
            }
            input.seek(end);
            first = false;
        }
        samples = new long[channels][maximumBlock];
        if (digest != null) hashBuffer = new byte[maximumBlock * channels * ((depth + 7) / 8)];
    }

    public Block readFrame() {
        if (closed || ended) return null;
        if (input.atEnd()) {
            require(total == 0 || decoded == total, "Truncated FLAC audio");
            if (digest != null) require(java.util.Arrays.equals(expectedHash, digest.digest()), "FLAC PCM MD5 mismatch");
            ended = true;
            return null;
        }
        int start = input.bytePosition();
        int sync = (int) input.read(16);
        require((sync & 0xfffe) == 0xfff8, "Invalid FLAC frame sync");
        int variable = sync & 1;
        if (blocking < 0) blocking = variable;
        require(blocking == variable, "FLAC blocking strategy changed");
        int sizeCode = (int) input.read(4), rateCode = (int) input.read(4);
        int channelCode = (int) input.read(4), depthCode = (int) input.read(3);
        require(input.read(1) == 0 && sizeCode != 0 && rateCode != 15 && depthCode != 3 && channelCode <= 10, "Invalid FLAC frame header");
        long number = codedNumber();
        require(number == (variable == 1 ? decoded : frames), "Nonsequential FLAC frame");
        int size;
        if (sizeCode == 1) size = 192;
        else if (sizeCode <= 5) size = 576 << (sizeCode - 2);
        else if (sizeCode == 6) size = (int) input.read(8) + 1;
        else if (sizeCode == 7) size = (int) input.read(16) + 1;
        else size = 256 << (sizeCode - 8);
        require(size > 0 && size <= maximumBlock && size <= 65535, "Invalid FLAC frame block size");
        int[] rates = {rate, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100, 48000, 96000};
        int frameRate = rateCode < 12 ? rates[rateCode] : (int) input.read(rateCode == 12 ? 8 : 16) * (rateCode == 12 ? 1000 : rateCode == 14 ? 10 : 1);
        int[] depths = {depth, 8, 12, 0, 16, 20, 24, 32};
        int count = channelCode < 8 ? channelCode + 1 : 2;
        require(frameRate == rate && depths[depthCode] == depth && count == channels, "FLAC sample format changed");
        int headerEnd = input.bytePosition();
        require(crc(input.data, start, headerEnd, 8) == input.read(8), "FLAC header CRC mismatch");
        for (int c = 0; c < channels; c++) {
            boolean side = (channelCode == 8 || channelCode == 10) && c == 1 || channelCode == 9 && c == 0;
            subframe(samples[c], size, depth + (side ? 1 : 0));
        }
        input.alignZero();
        int end = input.bytePosition();
        require(crc(input.data, start, end, 16) == input.read(16), "FLAC frame CRC mismatch");
        require(total == 0 || size <= total - decoded, "FLAC sample count mismatch");
        short[] pcm = new short[size * channels];
        int hashPosition = 0;
        for (int i = 0; i < size; i++) {
            long left = samples[0][i], right = channels == 2 ? samples[1][i] : 0;
            if (channelCode == 8) right = left - right;
            else if (channelCode == 9) left += right;
            else if (channelCode == 10) {
                long mid = (left << 1) | (right & 1);
                left = (mid + right) >> 1;
                right = (mid - right) >> 1;
            }
            pcm[i * channels] = output(left);
            if (channels == 2) pcm[i * channels + 1] = output(right);
            // STREAMINFO 的校验按原始位深算；高位深转 16 位之前也要保证解码样本正确。
            if (digest != null) {
                for (int c = 0; c < channels; c++) {
                    long value = c == 0 ? left : right;
                    for (int b = 0; b < (depth + 7) / 8; b++) { hashBuffer[hashPosition++] = (byte) value; value >>= 8; }
                }
            }
        }
        if (digest != null) digest.update(hashBuffer, 0, hashPosition);
        decoded += size;
        frames++;
        return new Block(pcm, rate, channels);
    }

    private short output(long sample) {
        require(sample >= -(1L << (depth - 1)) && sample < (1L << (depth - 1)), "FLAC sample overflow");
        // 现有播放和环境音效链路统一为 16 位，按原始位深缩放，不能直接截取低 16 位。
        return (short) (depth > 16 ? sample >> (depth - 16) : sample << (16 - depth));
    }

    private void subframe(long[] values, int size, int bits) {
        require(input.read(1) == 0, "Invalid FLAC subframe padding");
        int type = (int) input.read(6);
        int wasted = input.read(1) == 0 ? 0 : input.unary() + 1;
        require(wasted < bits, "Invalid FLAC wasted bits");
        int width = bits - wasted;
        if (type == 0) java.util.Arrays.fill(values, 0, size, input.signed(width));
        else if (type == 1) for (int i = 0; i < size; i++) values[i] = input.signed(width);
        else {
            boolean fixed = type >= 8 && type <= 12;
            require(fixed || type >= 32, "Reserved FLAC subframe type");
            int order = fixed ? type - 8 : type - 31;
            require(order < size, "Invalid FLAC predictor order");
            for (int i = 0; i < order; i++) values[i] = input.signed(width);
            long[] coefficients = null;
            int shift = 0;
            if (!fixed) {
                int precision = (int) input.read(4) + 1;
                require(precision <= 15, "Invalid FLAC predictor precision");
                shift = (int) input.signed(5);
                require(shift >= 0, "Invalid FLAC predictor shift");
                coefficients = new long[order];
                for (int j = 0; j < order; j++) coefficients[j] = input.signed(precision);
            }
            residual(values, size, order);
            for (int i = order; i < size; i++) {
                long prediction = 0;
                if (fixed) {
                    switch (order) {
                        case 1: prediction = values[i-1]; break;
                        case 2: prediction = 2*values[i-1] - values[i-2]; break;
                        case 3: prediction = 3*values[i-1] - 3*values[i-2] + values[i-3]; break;
                        case 4: prediction = 4*values[i-1] - 6*values[i-2] + 4*values[i-3] - values[i-4]; break;
                        default: break;
                    }
                } else {
                    for (int j = 0; j < order; j++) prediction += coefficients[j] * values[i-j-1];
                    prediction >>= shift;
                }
                values[i] += prediction;
                require(values[i] >= -(1L << (width - 1)) && values[i] < (1L << (width - 1)), "FLAC predictor overflow");
            }
        }
        if (wasted > 0) for (int i = 0; i < size; i++) values[i] <<= wasted;
    }

    private void residual(long[] values, int size, int order) {
        int method = (int) input.read(2);
        require(method <= 1, "Reserved FLAC residual method");
        int partition = (int) input.read(4), partitions = 1 << partition;
        require(size % partitions == 0 && size / partitions > order, "Invalid FLAC residual partition");
        int count = size / partitions, position = order, parameterBits = method == 0 ? 4 : 5;
        for (int p = 0; p < partitions; p++) {
            int rice = (int) input.read(parameterBits), end = (p + 1) * count;
            if (rice == (1 << parameterBits) - 1) {
                int width = (int) input.read(5);
                while (position < end) values[position++] = input.signed(width);
            } else {
                while (position < end) {
                    long quotient = input.unary();
                    require(quotient <= (0xfffffffeL >> rice), "FLAC residual overflow");
                    long folded = (quotient << rice) | input.read(rice);
                    require(folded <= 0xfffffffeL, "FLAC residual overflow");
                    values[position++] = (folded >>> 1) ^ -(folded & 1);
                }
            }
        }
    }

    private long codedNumber() {
        int lead = (int) input.read(8);
        if (lead < 128) return lead;
        int count = Integer.numberOfLeadingZeros((~lead & 255) << 24);
        require(count >= 2 && count <= 7, "Invalid FLAC coded number");
        long value = lead & (127 >> count);
        for (int i = 1; i < count; i++) {
            int next = (int) input.read(8);
            require((next & 192) == 128, "Invalid FLAC coded number continuation");
            value = (value << 6) | (next & 63);
        }
        long[] minimum = {0, 0, 128, 2048, 65536, 2097152, 67108864, 2147483648L};
        require(value >= minimum[count] && value < (1L << (blocking == 1 ? 36 : 31)), "Invalid FLAC coded number range");
        return value;
    }

    private static int[] crcTable(int bits, int polynomial) {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int value = i << (bits - 8);
            for (int b = 0; b < 8; b++) value = (value << 1) ^ ((value & (1 << (bits - 1))) != 0 ? polynomial : 0);
            table[i] = value & ((1 << bits) - 1);
        }
        return table;
    }
    private static int crc(byte[] bytes, int start, int end, int bits) {
        int value = 0;
        int[] table = bits == 8 ? CRC8 : CRC16;
        for (int i = start; i < end; i++) value = ((value << 8) ^ table[((value >> (bits - 8)) ^ bytes[i]) & 255]) & ((1 << bits) - 1);
        return value;
    }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    @Override public void close() { closed = true; }

    private static final class Bits {
        final byte[] data;
        int position;
        Bits(byte[] data) { this.data = data; }
        long read(int count) {
            require(count >= 0 && count <= 36 && (long) position + count <= (long) data.length * 8, "Truncated FLAC bitstream");
            long value = 0;
            int left = count;
            while (left > 0) {
                int available = 8 - (position & 7), take = Math.min(left, available);
                value = (value << take) | ((data[position >>> 3] >>> (available - take)) & ((1 << take) - 1));
                position += take;
                left -= take;
            }
            return value;
        }
        long signed(int count) { if (count == 0) return 0; long value = read(count); return (value << (64 - count)) >> (64 - count); }
        int unary() {
            int zeros = 0;
            while (true) {
                require(position < (long) data.length * 8, "Truncated FLAC unary code");
                int available = 8 - (position & 7), value = data[position >>> 3] & ((1 << available) - 1);
                if (value == 0) { zeros += available; position += available; }
                else {
                    int leading = Integer.numberOfLeadingZeros(value) - (32 - available);
                    position += leading + 1;
                    return zeros + leading;
                }
            }
        }
        void alignZero() { int padding = (8 - (position & 7)) & 7; require(read(padding) == 0, "Invalid FLAC frame padding"); }
        int bytePosition() { require((position & 7) == 0, "Unaligned FLAC field"); return position >>> 3; }
        boolean atEnd() { return (long) position == (long) data.length * 8; }
        void seek(int next) { require(next >= bytePosition() && next <= data.length, "Invalid FLAC metadata length"); position = next * 8; }
    }
}
