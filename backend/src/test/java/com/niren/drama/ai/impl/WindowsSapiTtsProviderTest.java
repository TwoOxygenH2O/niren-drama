package com.niren.drama.ai.impl;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsSapiTtsProviderTest {

    @Test
    void synthesizeReturnsRealWavOnWindows() {
        Assumptions.assumeTrue(WindowsSapiTtsProvider.isSupported());
        WindowsSapiTtsProvider provider = new WindowsSapiTtsProvider();

        byte[] audio = provider.synthesize("她从冷宫醒来，第一件事就是把欠她的人，一个一个写进账本。", "zh_female_zhubo", 1.0f, 1.0f);

        assertThat(audio).hasSizeGreaterThan(44_100);
        assertThat(new String(audio, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(audio, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
    }

    @Test
    void listVoicesIncludesChineseNarratorFallback() {
        WindowsSapiTtsProvider provider = new WindowsSapiTtsProvider();

        assertThat(provider.listVoices())
                .extracting("voiceId")
                .contains("zh_female_zhubo");
    }
}
