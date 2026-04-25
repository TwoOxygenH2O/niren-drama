package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.ai.TtsProvider;
import com.niren.drama.ai.VoiceInfo;
import com.niren.drama.ai.trace.AiTraceSupport;
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

    private final String providerName;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiTtsProvider(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, "openai");
    }

    public OpenAiTtsProvider(String baseUrl, String apiKey, String model, String providerName) {
        this.providerName = providerName != null && !providerName.isBlank() ? providerName : "openai";
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
        return synthesize(text, voiceId, speed, pitch, null, null);
    }

    @Override
    public byte[] synthesize(String text,
                             String voiceId,
                             float speed,
                             float pitch,
                             String instruction,
                             String languageType) {
        String endpoint = normalizeBaseUrl(baseUrl) + "/audio/speech";
        log.debug("开始 OpenAI 兼容 TTS 合成: provider={}, model={}, voiceId={}, textLength={}, speed={}, pitch={}, hasInstruction={}, languageType={}",
            providerName,
            model,
            voiceId != null && !voiceId.isBlank() ? voiceId : "alloy",
            text != null ? text.length() : 0,
            speed,
            pitch,
            instruction != null && !instruction.isBlank(),
            languageType);
        String requestBody = null;
        HttpResponse<byte[]> response = null;
        byte[] responseBytes = null;
        String responseBody = null;
        String error = null;
        Map<String, String> headers = AiTraceSupport.jsonHeaders(apiKey);
        try {
            String voice = (voiceId != null && !voiceId.isBlank()) ? voiceId : "alloy";
            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", text,
                    "voice", voice,
                    "speed", speed > 0 ? speed : 1.0f,
                    "response_format", "mp3"
            );

            requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            responseBytes = response.body();

            if (response.statusCode() != 200) {
                String errorBody = new String(responseBytes);
                responseBody = errorBody;
                error = "TTS API returned status " + response.statusCode() + ": " + errorBody;
                log.error("语音合成接口异常: 状态码={}, 响应体={}", response.statusCode(), errorBody);
                throw new RuntimeException(error);
            }

                log.debug("兼容 TTS 合成成功: provider={}, voice={}, textLength={}, audioSize={} 字节",
                    providerName,
                    voice, text.length(), responseBytes.length);
            return responseBytes;
        } catch (Exception e) {
            if (!AiTraceSupport.hasText(error)) {
                error = e.getMessage();
            }
            log.error("兼容 TTS 接口调用失败", e);
            throw new RuntimeException("TTS synthesis failed: " + e.getMessage(), e);
        } finally {
            AiTraceSupport.record(
                    "tts",
                    providerName,
                    "synthesize_speech",
                    "POST",
                    endpoint,
                    headers,
                    requestBody,
                    response != null ? response.statusCode() : null,
                    response != null ? response.headers().firstValue("Content-Type").orElse(null) : null,
                    responseBody,
                    responseBytes != null ? responseBytes.length : null,
                    response != null && response.statusCode() == 200 && responseBytes != null && responseBytes.length > 0,
                    null,
                    error);
        }
    }

    private String normalizeBaseUrl(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Override
    public List<VoiceInfo> listVoices() {
        // Standard OpenAI voices (available on all OpenAI-compatible APIs)
        // Chinese-specific voices (zh_*) are available on some providers like Volcengine/MiniMax
        List<VoiceInfo> voices = List.of(
                new VoiceInfo("alloy", "Alloy", "neutral", "en", "中性平稳的声音（通用）"),
                new VoiceInfo("echo", "Echo", "male", "en", "深沉有力的男声（通用）"),
                new VoiceInfo("fable", "Fable", "neutral", "en", "温暖叙事风格（通用）"),
                new VoiceInfo("onyx", "Onyx", "male", "en", "低沉磁性男声（通用）"),
                new VoiceInfo("nova", "Nova", "female", "en", "活力温暖女声（通用）"),
                new VoiceInfo("shimmer", "Shimmer", "female", "en", "清新柔和女声（通用）"),
                new VoiceInfo("zh_female_qingxin", "清新女声", "female", "zh-CN", "清新自然的女声（火山引擎/MiniMax）"),
                new VoiceInfo("zh_male_chunhou", "醇厚男声", "male", "zh-CN", "醇厚有力的男声（火山引擎/MiniMax）"),
                new VoiceInfo("zh_female_tianmei", "甜美女声", "female", "zh-CN", "甜美可爱的女声（火山引擎/MiniMax）"),
                new VoiceInfo("zh_male_mochen", "磁性男声", "male", "zh-CN", "磁性深沉的男声（火山引擎/MiniMax）")
        );
        log.debug("兼容 TTS 音色加载完成: provider={}, count={}", providerName, voices.size());
        return voices;
    }
}
