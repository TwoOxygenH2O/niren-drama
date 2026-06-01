package com.niren.drama.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigSecretCodecTest {

    @Test
    void encryptsAndDecryptsApiKey() {
        AiConfigSecretCodec codec = newCodec();

        String encrypted = codec.encrypt("sk-test-private-value");

        assertThat(encrypted).startsWith("enc:v1:");
        assertThat(encrypted).doesNotContain("sk-test-private-value");
        assertThat(codec.decrypt(encrypted)).isEqualTo("sk-test-private-value");
    }

    @Test
    void masksPlainOrEncryptedValuesForClientViews() {
        AiConfigSecretCodec codec = newCodec();

        String encrypted = codec.encrypt("sk-test-private-value");

        assertThat(codec.mask(encrypted)).isEqualTo("sk-t********alue");
        assertThat(codec.isMaskedValue("sk-t********alue")).isTrue();
    }

    private AiConfigSecretCodec newCodec() {
        AiConfigSecretCodec codec = new AiConfigSecretCodec();
        ReflectionTestUtils.setField(codec, "configuredKey", "unit-test-config-encryption-key");
        ReflectionTestUtils.setField(codec, "jwtSecret", "");
        return codec;
    }
}
