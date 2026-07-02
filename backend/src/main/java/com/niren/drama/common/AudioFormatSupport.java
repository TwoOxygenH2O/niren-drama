package com.niren.drama.common;

/**
 * Lightweight audio container sniffing for TTS providers.
 */
public final class AudioFormatSupport {

    private AudioFormatSupport() {
    }

    public static String extensionFor(byte[] audio) {
        if (isWav(audio)) {
            return "wav";
        }
        return "mp3";
    }

    public static String contentTypeFor(byte[] audio) {
        if (isWav(audio)) {
            return "audio/wav";
        }
        return "audio/mpeg";
    }

    public static String filename(String basename, byte[] audio) {
        String safeBase = basename != null && !basename.isBlank() ? basename.trim() : "audio";
        int dot = safeBase.lastIndexOf('.');
        if (dot > 0) {
            safeBase = safeBase.substring(0, dot);
        }
        return safeBase + "." + extensionFor(audio);
    }

    public static boolean isWav(byte[] audio) {
        return audio != null
                && audio.length >= 12
                && asciiEquals(audio, 0, "RIFF")
                && asciiEquals(audio, 8, "WAVE");
    }

    public static boolean isMp3(byte[] audio) {
        if (audio == null || audio.length < 3) {
            return false;
        }
        if (asciiEquals(audio, 0, "ID3")) {
            return true;
        }
        return audio.length >= 2
                && (audio[0] & 0xFF) == 0xFF
                && (audio[1] & 0xE0) == 0xE0;
    }

    private static boolean asciiEquals(byte[] audio, int offset, String value) {
        if (audio.length < offset + value.length()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (audio[offset + i] != (byte) value.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
