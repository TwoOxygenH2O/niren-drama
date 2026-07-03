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

    public static double durationSeconds(byte[] audio) {
        if (!isWav(audio)) {
            return -1d;
        }
        int offset = 12;
        Integer channels = null;
        Integer sampleRate = null;
        Integer bitsPerSample = null;
        Integer dataSize = null;
        while (offset + 8 <= audio.length) {
            String chunkId = ascii(audio, offset, 4);
            int chunkSize = readIntLE(audio, offset + 4);
            int chunkDataOffset = offset + 8;
            if (chunkSize < 0 || chunkDataOffset + chunkSize > audio.length) {
                break;
            }
            if ("fmt ".equals(chunkId) && chunkSize >= 16) {
                channels = readShortLE(audio, chunkDataOffset + 2);
                sampleRate = readIntLE(audio, chunkDataOffset + 4);
                bitsPerSample = readShortLE(audio, chunkDataOffset + 14);
            } else if ("data".equals(chunkId)) {
                dataSize = chunkSize;
            }
            offset = chunkDataOffset + chunkSize + (chunkSize % 2);
        }
        if (channels == null || sampleRate == null || bitsPerSample == null || dataSize == null
                || channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0) {
            return -1d;
        }
        double bytesPerSecond = sampleRate * channels * (bitsPerSample / 8.0d);
        return bytesPerSecond > 0d ? dataSize / bytesPerSecond : -1d;
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

    private static String ascii(byte[] audio, int offset, int length) {
        if (audio == null || offset < 0 || audio.length < offset + length) {
            return "";
        }
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append((char) audio[offset + i]);
        }
        return value.toString();
    }

    private static int readIntLE(byte[] audio, int offset) {
        if (audio == null || audio.length < offset + 4) {
            return -1;
        }
        return (audio[offset] & 0xFF)
                | ((audio[offset + 1] & 0xFF) << 8)
                | ((audio[offset + 2] & 0xFF) << 16)
                | ((audio[offset + 3] & 0xFF) << 24);
    }

    private static int readShortLE(byte[] audio, int offset) {
        if (audio == null || audio.length < offset + 2) {
            return -1;
        }
        return (audio[offset] & 0xFF) | ((audio[offset + 1] & 0xFF) << 8);
    }
}
