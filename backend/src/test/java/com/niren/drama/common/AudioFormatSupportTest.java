package com.niren.drama.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioFormatSupportTest {

    @Test
    void readsDurationFromPcmWavHeader() {
        byte[] wav = pcmWav(24_000, 1, 16, 2.5d);

        assertThat(AudioFormatSupport.durationSeconds(wav)).isCloseTo(2.5d, within(0.001d));
    }

    @Test
    void returnsNegativeDurationForUnsupportedAudio() {
        assertThat(AudioFormatSupport.durationSeconds(new byte[]{'I', 'D', '3', 0, 0, 0})).isLessThan(0d);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }

    private static byte[] pcmWav(int sampleRate, int channels, int bitsPerSample, double seconds) {
        int bytesPerSample = bitsPerSample / 8;
        int samples = (int) Math.round(sampleRate * seconds);
        int dataSize = samples * channels * bytesPerSample;
        byte[] wav = new byte[44 + dataSize];
        writeAscii(wav, 0, "RIFF");
        writeIntLE(wav, 4, 36 + dataSize);
        writeAscii(wav, 8, "WAVE");
        writeAscii(wav, 12, "fmt ");
        writeIntLE(wav, 16, 16);
        writeShortLE(wav, 20, (short) 1);
        writeShortLE(wav, 22, (short) channels);
        writeIntLE(wav, 24, sampleRate);
        writeIntLE(wav, 28, sampleRate * channels * bytesPerSample);
        writeShortLE(wav, 32, (short) (channels * bytesPerSample));
        writeShortLE(wav, 34, (short) bitsPerSample);
        writeAscii(wav, 36, "data");
        writeIntLE(wav, 40, dataSize);
        return wav;
    }

    private static void writeAscii(byte[] target, int offset, String value) {
        for (int i = 0; i < value.length(); i++) {
            target[offset + i] = (byte) value.charAt(i);
        }
    }

    private static void writeIntLE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void writeShortLE(byte[] target, int offset, short value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }
}
