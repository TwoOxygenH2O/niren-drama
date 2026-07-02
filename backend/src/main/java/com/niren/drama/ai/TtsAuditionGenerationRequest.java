package com.niren.drama.ai;

import java.util.List;

public record TtsAuditionGenerationRequest(
        String roleName,
        String text,
        String speakerReferenceAudio,
        String emotionReferenceAudio,
        String emotionText,
        List<Double> emotionVector,
        Double speed,
        Long seed,
        String filenamePrefix
) {
}
