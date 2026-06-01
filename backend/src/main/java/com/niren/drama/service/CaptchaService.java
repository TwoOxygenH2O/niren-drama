package com.niren.drama.service;

import com.niren.drama.dto.auth.CaptchaResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class CaptchaService {

    private static final String DATA_URL_PREFIX = "data:image/png;base64,";
    private static final String CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 4;
    private static final int IMAGE_WIDTH = 132;
    private static final int IMAGE_HEIGHT = 44;

    private final ConcurrentHashMap<String, CaptchaEntry> entries = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;
    private final Clock clock;
    private final Supplier<String> codeSupplier;

    @Autowired
    public CaptchaService(@Value("${niren.auth.captcha.ttl-seconds:120}") long ttlSeconds) {
        this(Duration.ofSeconds(ttlSeconds), Clock.systemDefaultZone(), null);
    }

    CaptchaService(Duration ttl, Clock clock, Supplier<String> codeSupplier) {
        this.ttl = ttl;
        this.clock = clock;
        this.codeSupplier = codeSupplier != null ? codeSupplier : this::randomCode;
    }

    public CaptchaResponse generateCaptcha() {
        cleanupExpiredEntries();
        String captchaId = UUID.randomUUID().toString();
        String code = codeSupplier.get().toUpperCase(Locale.ROOT);
        entries.put(captchaId, new CaptchaEntry(code, clock.instant().plus(ttl)));
        return new CaptchaResponse(captchaId, DATA_URL_PREFIX + renderPngBase64(code), ttl.toSeconds());
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
        if (!entry.code().equals(captchaCode.trim().toUpperCase(Locale.ROOT))) {
            entries.remove(captchaId);
            return false;
        }
        return entries.remove(captchaId, entry);
    }

    private void cleanupExpiredEntries() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(item -> item.getValue().expiresAt().isBefore(now));
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return builder.toString();
    }

    private String renderPngBase64(String code) {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(245, 247, 252));
            graphics.fillRoundRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT, 10, 10);
            drawNoise(graphics);
            drawCode(graphics, code);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("验证码图片生成失败", e);
        } finally {
            graphics.dispose();
        }
    }

    private void drawNoise(Graphics2D graphics) {
        graphics.setStroke(new BasicStroke(1.4f));
        for (int i = 0; i < 6; i++) {
            graphics.setColor(new Color(80 + random.nextInt(110), 100 + random.nextInt(100), 130 + random.nextInt(90), 95));
            int y = 8 + random.nextInt(IMAGE_HEIGHT - 16);
            graphics.drawLine(8, y, IMAGE_WIDTH - 8, 8 + random.nextInt(IMAGE_HEIGHT - 16));
        }
        for (int i = 0; i < 40; i++) {
            graphics.setColor(new Color(90 + random.nextInt(120), 100 + random.nextInt(100), 130 + random.nextInt(90), 115));
            graphics.fillOval(random.nextInt(IMAGE_WIDTH), random.nextInt(IMAGE_HEIGHT), 2, 2);
        }
    }

    private void drawCode(Graphics2D graphics, String code) {
        Font font = new Font("SansSerif", Font.BOLD, 25);
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        int totalWidth = metrics.stringWidth(code);
        int startX = (IMAGE_WIDTH - totalWidth) / 2;
        int baseline = (IMAGE_HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent();

        for (int i = 0; i < code.length(); i++) {
            graphics.setColor(new Color(22 + random.nextInt(45), 44 + random.nextInt(45), 80 + random.nextInt(55)));
            int x = startX + metrics.stringWidth(code.substring(0, i));
            int y = baseline + random.nextInt(5) - 2;
            graphics.drawString(String.valueOf(code.charAt(i)), x, y);
        }
    }

    private record CaptchaEntry(String code, Instant expiresAt) {
    }
}
