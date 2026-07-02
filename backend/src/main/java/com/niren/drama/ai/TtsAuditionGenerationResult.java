package com.niren.drama.ai;

public record TtsAuditionGenerationResult(
        byte[] audio,
        String promptId,
        String outputUrl,
        String workflowFile,
        Double durationSeconds
) {
}
