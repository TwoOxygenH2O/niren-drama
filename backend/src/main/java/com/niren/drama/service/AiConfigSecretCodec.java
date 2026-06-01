package com.niren.drama.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class AiConfigSecretCodec {

    private static final String PREFIX = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${niren.security.config-encryption-key:}")
    private String configuredKey;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    public String encrypt(String plainText) {
        if (!hasText(plainText) || isEncrypted(plainText)) {
            return plainText;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + cipherText.length)
                    .put(iv)
                    .put(cipherText)
                    .array();
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt AI configuration secret", e);
        }
    }

    public String decrypt(String value) {
        if (!isEncrypted(value)) {
            return value;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(value.substring(PREFIX.length()));
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt AI configuration secret", e);
        }
    }

    public String mask(String value) {
        String plainText = decrypt(value);
        if (!hasText(plainText)) {
            return "";
        }
        String trimmed = plainText.trim();
        if (trimmed.length() <= 8) {
            return "********";
        }
        return trimmed.substring(0, 4) + "********" + trimmed.substring(trimmed.length() - 4);
    }

    public boolean isMaskedValue(String value) {
        return hasText(value) && value.contains("****");
    }

    public boolean isEncrypted(String value) {
        return hasText(value) && value.startsWith(PREFIX);
    }

    private SecretKeySpec keySpec() throws Exception {
        String material = hasText(configuredKey) ? configuredKey : jwtSecret;
        if (!hasText(material)) {
            throw new IllegalStateException("NIREN_CONFIG_ENCRYPTION_KEY or JWT_SECRET must be configured.");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
