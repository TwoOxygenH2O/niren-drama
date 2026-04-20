package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.ai.TtsProvider;
import com.niren.drama.ai.VoiceInfo;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible TTS provider.
 * Calls POST /audio/speech with model, input, voice parameters.
 * Returns raw audio bytes (MP3 format).
 */
@Slf4j
public class OpenAiTtsProvider implements TtsProvider {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiTtsProvider(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : "tts-1";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public byte[] synthesize(String text, String voiceId, float speed, float pitch) {
        try {
            String voice = (voiceId != null && !voiceId.isBlank()) ? voiceId : "alloy";
            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", text,
                    "voice", voice,
                    "speed", speed > 0 ? speed : 1.0f,
                    "response_format", "mp3"
            );

            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/audio/speech"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body());
                log.error("TTS API error (status {}): {}", response.statusCode(), errorBody);
                throw new RuntimeException("TTS API returned status " + response.statusCode() + ": " + errorBody);
            }

            log.info("TTS synthesize success: voice={}, textLength={}, audioSize={} bytes",
                    voice, text.length(), response.body().length);
            return response.body();
        } catch (Exception e) {
            log.error("OpenAI TTS API call failed", e);
            throw new RuntimeException("TTS synthesis failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<VoiceInfo> listVoices() {
        return List.of(
                new VoiceInfo("alloy", "Alloy", "neutral", "en", "中性平稳的声音"),
                new VoiceInfo("echo", "Echo", "male", "en", "深沉有力的男声"),
                new VoiceInfo("fable", "Fable", "neutral", "en", "温暖叙事风格"),
                new VoiceInfo("onyx", "Onyx", "male", "en", "低沉磁性男声"),
                new VoiceInfo("nova", "Nova", "female", "en", "活力温暖女声"),
                new VoiceInfo("shimmer", "Shimmer", "female", "en", "清新柔和女声"),
                new VoiceInfo("zh_female_qingxin", "清新女声", "female", "zh-CN", "清新自然的女声"),
                new VoiceInfo("zh_male_chunhou", "醇厚男声", "male", "zh-CN", "醇厚有力的男声"),
                new VoiceInfo("zh_female_tianmei", "甜美女声", "female", "zh-CN", "甜美可爱的女声"),
                new VoiceInfo("zh_male_mochen", "磁性男声", "male", "zh-CN", "磁性深沉的男声")
        );
    }
}
