package com.jcraft.jorbis;

import java.io.InputStream;

/** 直接读取浮点 PCM；旧版 read 的部分分支忽略样本偏移，不能用于连续分块输出。 */
public final class DangoVorbis extends VorbisFile {
    public DangoVorbis(InputStream input) throws JOrbisException { super(input, null, 0); }

    public int readPcm(byte[] target) {
        float[][][] channels = new float[1][][];
        while (true) {
            if (decode_ready) {
                Info info = getInfo(-1);
                if (info.channels < 1 || info.channels > 2) throw new IllegalStateException("Unsupported Vorbis channels");
                int[] offsets = new int[info.channels];
                int available = vd.synthesis_pcmout(channels, offsets);
                if (available > 0) {
                    int frames = Math.min(available, target.length / (2 * info.channels));
                    if (frames == 0) throw new IllegalArgumentException("PCM buffer too small");
                    // 每个声道有独立的读取偏移；输出是逐样本交错的有符号小端 16 位。
                    int pos = 0;
                    for (int frame = 0; frame < frames; frame++) {
                        for (int channel = 0; channel < info.channels; channel++) {
                            float value = channels[0][channel][offsets[channel] + frame];
                            int sample = Math.max(-32768, Math.min(32767, Math.round(value * 32768f)));
                            target[pos++] = (byte) sample;
                            target[pos++] = (byte) (sample >> 8);
                        }
                    }
                    vd.synthesis_read(frames);
                    pcm_offset += frames;
                    return pos;
                }
            }
            int result = process_packet(1);
            if (result <= 0) return result;
        }
    }
}
