package com.niren.drama.ai;

import java.util.List;

public interface TtsProvider {

    /**
     * Synthesize speech and return raw audio bytes (WAV/MP3).
     */
    byte[] synthesize(String text, String voiceId, float speed, float pitch);

    /**
     * Synthesize speech with optional provider-specific instruction and language hints.
     */
    default byte[] synthesize(String text,
                              String voiceId,
                              float speed,
                              float pitch,
                              String instruction,
                              String languageType) {
        return synthesize(text, voiceId, speed, pitch);
    }

    /**
     * List available voices.
     */
    List<VoiceInfo> listVoices();
}
