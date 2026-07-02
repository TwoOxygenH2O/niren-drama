package com.niren.drama.ai.impl;

import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.AiResolvedConfig;
import com.niren.drama.ai.TtsAuditionAudioGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ComfyUiTtsProviderFactory {

    private final AiProviderFactory aiProviderFactory;

    @Value("${niren.ai.tts.workflow-file:tts_indextts2_audition.json}")
    private String defaultWorkflowFile;

    @Value("${niren.ai.tts.audition.max-poll-attempts:900}")
    private int maxPollAttempts;

    @Value("${niren.ai.tts.audition.poll-interval-ms:2000}")
    private long pollIntervalMs;

    @Value("${niren.ai.video.base-url:http://127.0.0.1:8188}")
    private String defaultComfyBaseUrl;

    public TtsAuditionAudioGenerator create(Long userId) {
        AiResolvedConfig tts = aiProviderFactory.resolveConfig(userId, "tts");
        AiResolvedConfig video = aiProviderFactory.resolveConfig(userId, "video");
        boolean ttsUsesComfy = "comfyui".equalsIgnoreCase(tts.provider());
        String baseUrl = firstNonBlank(
                ttsUsesComfy ? tts.baseUrl() : null,
                video.baseUrl(),
                defaultComfyBaseUrl);
        String apiKey = firstNonBlank(ttsUsesComfy ? tts.apiKey() : null, video.apiKey(), "");
        String workflowFile = firstNonBlank(ttsUsesComfy ? tts.model() : null, defaultWorkflowFile);
        String extra = ttsUsesComfy ? tts.extra() : null;
        return new ComfyUiTtsProvider(baseUrl, apiKey, workflowFile, extra, maxPollAttempts, pollIntervalMs);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
