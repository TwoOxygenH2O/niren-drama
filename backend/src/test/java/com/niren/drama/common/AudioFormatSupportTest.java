package com.niren.drama.common;

import com.niren.drama.ai.impl.MockTtsProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioFormatSupportTest {

    @Test
    void detectsWavAudioFromBytes() {
        byte[] wav = new MockTtsProvider().synthesize("她低声念出旧案真相。", "narrator", 1.0f, 1.0f);

        assertThat(AudioFormatSupport.extensionFor(wav)).isEqualTo("wav");
        assertThat(AudioFormatSupport.contentTypeFor(wav)).isEqualTo("audio/wav");
        assertThat(AudioFormatSupport.filename("sample", wav)).isEqualTo("sample.wav");
    }

    @Test
    void detectsMp3AudioFromId3Header() {
        byte[] mp3 = new byte[] {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};

        assertThat(AudioFormatSupport.extensionFor(mp3)).isEqualTo("mp3");
        assertThat(AudioFormatSupport.contentTypeFor(mp3)).isEqualTo("audio/mpeg");
        assertThat(AudioFormatSupport.filename("sample", mp3)).isEqualTo("sample.mp3");
    }
}
