package com.niren.drama.ai.impl;

import com.niren.drama.ai.TtsProvider;
import com.niren.drama.ai.VoiceInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Mock TTS provider used when no real TTS provider is configured.
 * Returns a minimal valid WAV header as placeholder audio.
 */
@Slf4j
public class MockTtsProvider implements TtsProvider {

    @Override
    public byte[] synthesize(String text, String voiceId, float speed, float pitch) {
        log.info("Mock TTS synthesize: voiceId={}, textLength={}", voiceId, text.length());
        // Minimal WAV RIFF header placeholder
        return new byte[]{0x52, 0x49, 0x46, 0x46};
    }

    @Override
    public List<VoiceInfo> listVoices() {
        return List.of(
            new VoiceInfo("zh_female_qingxin", "清新女声", "female", "zh-CN", "清新自然的女声"),
            new VoiceInfo("zh_male_chunhou", "醇厚男声", "male", "zh-CN", "醇厚有力的男声"),
            new VoiceInfo("zh_female_tianmei", "甜美女声", "female", "zh-CN", "甜美可爱的女声"),
            new VoiceInfo("zh_male_mochen", "磁性男声", "male", "zh-CN", "磁性深沉的男声"),
            new VoiceInfo("zh_female_zhubo", "播音女声", "female", "zh-CN", "专业播音主持风格"),
            new VoiceInfo("zh_male_zhubo", "播音男声", "male", "zh-CN", "专业播音主持风格")
        );
    }
}
