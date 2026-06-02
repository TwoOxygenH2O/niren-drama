package com.niren.drama.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigSecretCodecTest {
    private static final String SAMPLE_SECRET = "unit-test-private-provider-value";
    private static final String SAMPLE_MASK = "unit********alue";

    @Test
    void encryptsAndDecryptsApiKey() {
        AiConfigSecretCodec codec = newCodec();

        String encrypted = codec.encrypt(SAMPLE_SECRET);

        assertThat(encrypted).startsWith("enc:v1:");
        assertThat(encrypted).doesNotContain(SAMPLE_SECRET);
        assertThat(codec.decrypt(encrypted)).isEqualTo(SAMPLE_SECRET);
    }

    @Test
    void masksPlainOrEncryptedValuesForClientViews() {
        AiConfigSecretCodec codec = newCodec();

        String encrypted = codec.encrypt(SAMPLE_SECRET);

        assertThat(codec.mask(encrypted)).isEqualTo(SAMPLE_MASK);
        assertThat(codec.isMaskedValue(SAMPLE_MASK)).isTrue();
    }

    private AiConfigSecretCodec newCodec() {
        AiConfigSecretCodec codec = new AiConfigSecretCodec();
        ReflectionTestUtils.setField(codec, "configuredKey", "unit-test-config-encryption-key");
        ReflectionTestUtils.setField(codec, "jwtSecret", "");
        return codec;
    }
}
