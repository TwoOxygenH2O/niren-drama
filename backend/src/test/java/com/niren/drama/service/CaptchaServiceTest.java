package com.niren.drama.service;

import com.niren.drama.dto.auth.CaptchaResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CaptchaServiceTest {

    @Test
    void generateCaptchaReturnsIdImageAndExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        CaptchaService service = new CaptchaService(Duration.ofSeconds(90), clock, () -> "A7K2");

        CaptchaResponse response = service.generateCaptcha();

        assertThat(response.getCaptchaId()).isNotBlank();
        assertThat(response.getImage()).startsWith("data:image/png;base64,");
        assertThat(response.getExpiresIn()).isEqualTo(90);
    }

    @Test
    void validateCaptchaConsumesWrongCode() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        CaptchaService service = new CaptchaService(Duration.ofSeconds(90), clock, () -> "A7K2");
        CaptchaResponse response = service.generateCaptcha();

        assertThat(service.validateCaptcha(response.getCaptchaId(), "0000")).isFalse();
        assertThat(service.validateCaptcha(response.getCaptchaId(), "a7k2")).isFalse();
    }

    @Test
    void validateCaptchaConsumesSuccessfulCode() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        CaptchaService service = new CaptchaService(Duration.ofSeconds(90), clock, () -> "A7K2");
        CaptchaResponse response = service.generateCaptcha();

        assertThat(service.validateCaptcha(response.getCaptchaId(), "A7K2")).isTrue();
        assertThat(service.validateCaptcha(response.getCaptchaId(), "A7K2")).isFalse();
    }

    @Test
    void validateCaptchaRejectsExpiredCode() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        CaptchaService service = new CaptchaService(Duration.ofSeconds(90), clock, () -> "A7K2");
        CaptchaResponse response = service.generateCaptcha();

        clock.advance(Duration.ofSeconds(91));

        assertThat(service.validateCaptcha(response.getCaptchaId(), "A7K2")).isFalse();
    }

    private static class MutableClock extends Clock {
        private final ZoneId zone = ZoneId.of("UTC");
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initialInstant) {
            this.instant = new AtomicReference<>(initialInstant);
        }

        private void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant.get(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
