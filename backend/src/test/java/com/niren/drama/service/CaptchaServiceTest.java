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
    void generateCaptchaReturnsBehaviorChallengeAndExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        CaptchaService service = new CaptchaService(Duration.ofSeconds(90), clock, () -> 64);

        CaptchaResponse response = service.generateCaptcha();

        assertThat(response.getCaptchaId()).isNotBlank();
        assertThat(response.getImage()).isNull();
        assertThat(response.getExpiresIn()).isEqualTo(90);
        assertThat(response.getMode()).isEqualTo("PASSIVE");
        assertThat(response.getSliderTarget()).isEqualTo(64);
        assertThat(response.getSliderTolerance()).isEqualTo(4);
        assertThat(response.getScene()).isEqualTo("director-track");
    }

    @Test
    void validateCaptchaConsumesBadBehaviorProof() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        CaptchaService service = new CaptchaService(Duration.ofSeconds(90), clock, () -> 64);
        CaptchaResponse response = service.generateCaptcha();

        assertThat(service.validateCaptcha(response.getCaptchaId(), "PASSIVE:30:200:1")).isFalse();
        assertThat(service.validateCaptcha(response.getCaptchaId(), "PASSIVE:90:1000:8")).isFalse();
    }

    @Test
    void validateCaptchaConsumesSuccessfulBehaviorProof() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        CaptchaService service = new CaptchaService(Duration.ofSeconds(90), clock, () -> 64);
        CaptchaResponse response = service.generateCaptcha();

        assertThat(service.validateCaptcha(response.getCaptchaId(), "PASSIVE:82:1200:6")).isTrue();
        assertThat(service.validateCaptcha(response.getCaptchaId(), "PASSIVE:82:1200:6")).isFalse();
    }

    @Test
    void validateCaptchaAcceptsSliderWithinTolerance() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        CaptchaService service = new CaptchaService(Duration.ofSeconds(90), clock, () -> 64);
        CaptchaResponse response = service.generateCaptcha();

        assertThat(service.validateCaptcha(response.getCaptchaId(), "SLIDER:67:520:8")).isTrue();
    }

    @Test
    void validateCaptchaRejectsSliderOutsideTolerance() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        CaptchaService service = new CaptchaService(Duration.ofSeconds(90), clock, () -> 64);
        CaptchaResponse response = service.generateCaptcha();

        assertThat(service.validateCaptcha(response.getCaptchaId(), "SLIDER:72:520:8")).isFalse();
    }

    @Test
    void validateCaptchaRejectsExpiredProof() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        CaptchaService service = new CaptchaService(Duration.ofSeconds(90), clock, () -> 64);
        CaptchaResponse response = service.generateCaptcha();

        clock.advance(Duration.ofSeconds(91));

        assertThat(service.validateCaptcha(response.getCaptchaId(), "PASSIVE:82:1200:6")).isFalse();
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
