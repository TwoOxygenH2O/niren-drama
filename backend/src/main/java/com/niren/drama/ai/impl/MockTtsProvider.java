package com.niren.drama.ai.impl;

import com.niren.drama.ai.TtsProvider;
import com.niren.drama.ai.VoiceInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Mock TTS provider used when no real TTS provider is configured.
 * Returns a short valid PCM WAV placeholder so local composition can proceed.
 */
@Slf4j
public class MockTtsProvider implements TtsProvider {

    private static final int SAMPLE_RATE = 24_000;
    private static final short CHANNELS = 1;
    private static final short BITS_PER_SAMPLE = 16;

    @Override
    public byte[] synthesize(String text, String voiceId, float speed, float pitch) {
        int textLength = text != null ? text.length() : 0;
        log.info("Mock TTS synthesize: voiceId={}, textLength={}", voiceId, textLength);
        return generatePlaceholderWav(textLength, speed, pitch);
    }

    @Override
    public byte[] synthesize(String text,
                             String voiceId,
                             float speed,
                             float pitch,
                             String instruction,
                             String languageType) {
        return synthesize(text, voiceId, speed, pitch);
    }

    @Override
    public List<VoiceInfo> listVoices() {
        return List.of(
            new VoiceInfo("zh_female_qingxin", "Chinese Female Fresh", "female", "zh-CN", "Fresh natural female voice"),
            new VoiceInfo("zh_male_chunhou", "Chinese Male Warm", "male", "zh-CN", "Warm steady male voice"),
            new VoiceInfo("zh_female_tianmei", "Chinese Female Sweet", "female", "zh-CN", "Sweet female voice"),
            new VoiceInfo("zh_male_mochen", "Chinese Male Deep", "male", "zh-CN", "Deep magnetic male voice"),
            new VoiceInfo("zh_female_zhubo", "Chinese Female Narrator", "female", "zh-CN", "Professional narrator style"),
            new VoiceInfo("zh_male_zhubo", "Chinese Male Narrator", "male", "zh-CN", "Professional narrator style")
        );
    }

    private byte[] generatePlaceholderWav(int textLength, float speed, float pitch) {
        double normalizedSpeed = speed > 0 ? speed : 1.0d;
        double durationSeconds = Math.min(4.0d, Math.max(0.8d, (0.45d + textLength * 0.08d) / normalizedSpeed));
        int samples = Math.max(1, (int) Math.round(SAMPLE_RATE * durationSeconds));
        int dataSize = samples * CHANNELS * (BITS_PER_SAMPLE / 8);
        byte[] wav = new byte[44 + dataSize];

        writeAscii(wav, 0, "RIFF");
        writeIntLE(wav, 4, 36 + dataSize);
        writeAscii(wav, 8, "WAVE");
        writeAscii(wav, 12, "fmt ");
        writeIntLE(wav, 16, 16);
        writeShortLE(wav, 20, (short) 1);
        writeShortLE(wav, 22, CHANNELS);
        writeIntLE(wav, 24, SAMPLE_RATE);
        int byteRate = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8);
        writeIntLE(wav, 28, byteRate);
        writeShortLE(wav, 32, (short) (CHANNELS * (BITS_PER_SAMPLE / 8)));
        writeShortLE(wav, 34, BITS_PER_SAMPLE);
        writeAscii(wav, 36, "data");
        writeIntLE(wav, 40, dataSize);

        double frequency = 330.0d * Math.max(0.5d, Math.min(1.5d, pitch > 0 ? pitch : 1.0d));
        double amplitude = 0.12d * Short.MAX_VALUE;
        for (int i = 0; i < samples; i++) {
            double envelope = Math.min(1.0d, Math.min(i / (SAMPLE_RATE * 0.05d), (samples - i) / (SAMPLE_RATE * 0.08d)));
            short value = (short) Math.round(Math.sin(2.0d * Math.PI * frequency * i / SAMPLE_RATE) * amplitude * Math.max(0.0d, envelope));
            writeShortLE(wav, 44 + i * 2, value);
        }
        return wav;
    }

    private void writeAscii(byte[] target, int offset, String value) {
        for (int i = 0; i < value.length(); i++) {
            target[offset + i] = (byte) value.charAt(i);
        }
    }

    private void writeIntLE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private void writeShortLE(byte[] target, int offset, short value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }
}
