package com.niren.drama.ai.impl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MockTtsProviderTest {

    @Test
    void synthesizeReturnsPlayableWavPlaceholder() {
        MockTtsProvider provider = new MockTtsProvider();

        byte[] audio = provider.synthesize("从今往后，山水不相逢。", "Cherry", 1.0f, 1.0f);

        assertThat(audio).hasSizeGreaterThan(100);
        assertThat(new String(audio, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(audio, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(new String(audio, 12, 4, StandardCharsets.US_ASCII)).isEqualTo("fmt ");
        assertThat(new String(audio, 36, 4, StandardCharsets.US_ASCII)).isEqualTo("data");
    }
}
