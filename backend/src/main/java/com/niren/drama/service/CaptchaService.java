package com.niren.drama.service;

import com.niren.drama.dto.auth.CaptchaResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class CaptchaService {

    private static final String MODE_PASSIVE = "PASSIVE";
    private static final String MODE_SLIDER = "SLIDER";
    private static final int PASSIVE_MIN_SCORE = 58;
    private static final int PASSIVE_MIN_DURATION_MS = 650;
    private static final int PASSIVE_MIN_EVENTS = 3;
    private static final int SLIDER_MIN_TARGET = 24;
    private static final int SLIDER_MAX_TARGET = 76;
    private static final int SLIDER_TOLERANCE = 4;
    private static final int SLIDER_MIN_DURATION_MS = 300;
    private static final int SLIDER_MIN_MOVES = 3;

    private final ConcurrentHashMap<String, CaptchaEntry> entries = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;
    private final Clock clock;
    private final Supplier<Integer> sliderTargetSupplier;

    @Autowired
    public CaptchaService(@Value("${niren.auth.captcha.ttl-seconds:120}") long ttlSeconds) {
        this(Duration.ofSeconds(ttlSeconds), Clock.systemDefaultZone(), null);
    }

    CaptchaService(Duration ttl, Clock clock, Supplier<Integer> sliderTargetSupplier) {
        this.ttl = ttl;
        this.clock = clock;
        this.sliderTargetSupplier = sliderTargetSupplier != null ? sliderTargetSupplier : this::randomSliderTarget;
    }

    public CaptchaResponse generateCaptcha() {
        cleanupExpiredEntries();
        String captchaId = UUID.randomUUID().toString();
        int sliderTarget = normalizeSliderTarget(sliderTargetSupplier.get());
        entries.put(captchaId, new CaptchaEntry(sliderTarget, clock.instant().plus(ttl)));

        CaptchaResponse response = new CaptchaResponse();
        response.setCaptchaId(captchaId);
        response.setExpiresIn(ttl.toSeconds());
        response.setMode(MODE_PASSIVE);
        response.setSliderTarget(sliderTarget);
        response.setSliderTolerance(SLIDER_TOLERANCE);
        response.setScene("director-track");
        return response;
    }

    public boolean validateCaptcha(String captchaId, String captchaCode) {
        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            return false;
        }
        CaptchaEntry entry = entries.get(captchaId);
        if (entry == null || entry.expiresAt().isBefore(clock.instant())) {
            entries.remove(captchaId);
            return false;
        }
        if (!validateProof(entry, captchaCode.trim())) {
            entries.remove(captchaId);
            return false;
        }
        return entries.remove(captchaId, entry);
    }

    private void cleanupExpiredEntries() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(item -> item.getValue().expiresAt().isBefore(now));
    }

    private boolean validateProof(CaptchaEntry entry, String proof) {
        String[] parts = proof.split(":");
        if (parts.length < 2) {
            return false;
        }
        String mode = parts[0].trim().toUpperCase(Locale.ROOT);
        if (MODE_PASSIVE.equals(mode)) {
            return validatePassiveProof(parts);
        }
        if (MODE_SLIDER.equals(mode)) {
            return validateSliderProof(entry, parts);
        }
        return false;
    }

    private boolean validatePassiveProof(String[] parts) {
        if (parts.length != 4) {
            return false;
        }
        Integer score = parseInteger(parts[1]);
        Integer durationMs = parseInteger(parts[2]);
        Integer eventCount = parseInteger(parts[3]);
        if (score == null || durationMs == null || eventCount == null) {
            return false;
        }
        return score >= PASSIVE_MIN_SCORE
                && durationMs >= PASSIVE_MIN_DURATION_MS
                && eventCount >= PASSIVE_MIN_EVENTS;
    }

    private boolean validateSliderProof(CaptchaEntry entry, String[] parts) {
        if (parts.length != 4) {
            return false;
        }
        Integer sliderValue = parseInteger(parts[1]);
        Integer durationMs = parseInteger(parts[2]);
        Integer moveCount = parseInteger(parts[3]);
        if (sliderValue == null || durationMs == null || moveCount == null) {
            return false;
        }
        return Math.abs(sliderValue - entry.sliderTarget()) <= SLIDER_TOLERANCE
                && durationMs >= SLIDER_MIN_DURATION_MS
                && moveCount >= SLIDER_MIN_MOVES;
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int randomSliderTarget() {
        return SLIDER_MIN_TARGET + random.nextInt(SLIDER_MAX_TARGET - SLIDER_MIN_TARGET + 1);
    }

    private int normalizeSliderTarget(Integer value) {
        if (value == null) {
            return randomSliderTarget();
        }
        return Math.max(SLIDER_MIN_TARGET, Math.min(SLIDER_MAX_TARGET, value));
    }

    private record CaptchaEntry(int sliderTarget, Instant expiresAt) {
    }
}
