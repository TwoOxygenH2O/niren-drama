package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

@Slf4j
public class AliyunTtsProvider implements TtsProvider {

    private final String providerName;
    private final String apiBaseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AliyunTtsProvider(String baseUrl, String apiKey, String model, String providerName) {
        this.providerName = providerName != null && !providerName.isBlank() ? providerName : "aliyun";
        this.apiBaseUrl = normalizeApiBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : "qwen3-tts-instruct-flash";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public byte[] synthesize(String text, String voiceId, float speed, float pitch) {
        String endpoint = apiBaseUrl + "/services/aigc/multimodal-conversation/generation";
        String requestBody = null;
        HttpResponse<String> generationResponse = null;
        String generationResponseBody = null;
        String audioUrl = null;
        String error = null;
        Map<String, String> headers = AiTraceSupport.jsonHeaders(apiKey);

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);

            ObjectNode input = body.putObject("input");
            ArrayNode messages = input.putArray("messages");
            ObjectNode userMessage = messages.addObject();
            userMessage.put("role", "user");
            ArrayNode content = userMessage.putArray("content");
            content.addObject().put("text", text);

            ObjectNode parameters = body.putObject("parameters");
            parameters.put("voice", voiceId != null && !voiceId.isBlank() ? voiceId : "Cherry");
            String instructions = buildInstructions(speed, pitch);
            if (!instructions.isBlank()) {
                parameters.put("instructions", instructions);
                parameters.put("optimize_instructions", true);
            }

            requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(180))
                    .build();

            generationResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            generationResponseBody = generationResponse.body();
            if (generationResponse.statusCode() >= 400) {
                error = "HTTP " + generationResponse.statusCode() + " - " + generationResponseBody;
                throw new RuntimeException(error);
            }

            JsonNode root = objectMapper.readTree(generationResponseBody);
            audioUrl = firstText(
                    root.path("output").path("audio").path("url"),
                    root.path("data").path("audio").path("url"),
                    root.path("audio").path("url"),
                    root.path("output").path("url"));
            if (audioUrl == null || audioUrl.isBlank()) {
                error = "阿里云 TTS 接口未返回音频地址: " + generationResponseBody;
                throw new RuntimeException(error);
            }

            AiTraceSupport.record(
                    "tts",
                    providerName,
                    "synthesize_speech",
                    "POST",
                    endpoint,
                    headers,
                    requestBody,
                    generationResponse.statusCode(),
                    generationResponse.headers().firstValue("Content-Type").orElse(null),
                    generationResponseBody,
                    generationResponseBody.length(),
                    true,
                    audioUrl,
                    null);

            HttpRequest downloadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(audioUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(180))
                    .build();
            HttpResponse<byte[]> audioResponse = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (audioResponse.statusCode() >= 400) {
                throw new RuntimeException("音频下载失败: HTTP " + audioResponse.statusCode());
            }

            AiTraceSupport.record(
                    "tts",
                    providerName,
                    "download_audio",
                    "GET",
                    audioUrl,
                    Map.of(),
                    null,
                    audioResponse.statusCode(),
                    audioResponse.headers().firstValue("Content-Type").orElse(null),
                    null,
                    audioResponse.body() != null ? audioResponse.body().length : null,
                    audioResponse.body() != null && audioResponse.body().length > 0,
                    audioUrl,
                    null);
            return audioResponse.body();
        } catch (Exception e) {
            if (error == null || error.isBlank()) {
                error = e.getMessage();
            }
            AiTraceSupport.record(
                    "tts",
                    providerName,
                    "synthesize_speech",
                    "POST",
                    endpoint,
                    headers,
                    requestBody,
                    generationResponse != null ? generationResponse.statusCode() : null,
                    generationResponse != null ? generationResponse.headers().firstValue("Content-Type").orElse(null) : null,
                    generationResponseBody,
                    generationResponseBody != null ? generationResponseBody.length() : null,
                    false,
                    audioUrl,
                    error);
            log.error("Aliyun TTS API call failed", e);
            throw new RuntimeException("TTS synthesis failed: " + error, e);
        }
    }

    @Override
    public List<VoiceInfo> listVoices() {
        return List.of(new VoiceInfo("Cherry", "Cherry", "female", "zh-CN", "阿里云 Qwen TTS 官方示例音色"));
    }

    private String buildInstructions(float speed, float pitch) {
        StringBuilder builder = new StringBuilder();
        if (speed > 1.08f) {
            builder.append("The speaking speed is fast.");
        } else if (speed > 0 && speed < 0.92f) {
            builder.append("The speaking speed is slow.");
        }
        if (pitch > 1.08f) {
            if (builder.length() > 0) builder.append(' ');
            builder.append("Use a slightly higher pitch.");
        } else if (pitch > 0 && pitch < 0.92f) {
            if (builder.length() > 0) builder.append(' ');
            builder.append("Use a slightly lower pitch.");
        }
        return builder.toString();
    }

    private String firstText(JsonNode... nodes) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            String text = node.asText(null);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private String normalizeApiBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "https://dashscope.aliyuncs.com/api/v1";
        }
        String normalized = trimTrailingSlash(value.trim());
        String[] suffixes = new String[] {
                "/services/aigc/multimodal-conversation/generation",
                "/services/aigc/multimodal-generation/generation",
                "/api/v1"
        };
        boolean stripped;
        do {
            stripped = false;
            for (String suffix : suffixes) {
                if (normalized.endsWith(suffix)) {
                    normalized = trimTrailingSlash(normalized.substring(0, normalized.length() - suffix.length()));
                    stripped = true;
                    break;
                }
            }
        } while (stripped);
        return normalized + "/api/v1";
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}