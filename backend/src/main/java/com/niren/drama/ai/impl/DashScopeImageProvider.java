package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.niren.drama.ai.ImageAiProvider;
import com.niren.drama.ai.trace.AiTraceSupport;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DashScope (Alibaba Cloud Bailian) image provider.
 * Uses async task-based image generation with polling.
 * API docs: https://help.aliyun.com/zh/model-studio/
 */
@Slf4j
public class DashScopeImageProvider implements ImageAiProvider {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final int MAX_POLL_ATTEMPTS = 60;
    private static final long POLL_INTERVAL_MS = 3000;
    private static final String DEFAULT_REFERENCE_EDIT_MODEL = "qwen-image-edit";

    public DashScopeImageProvider(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : "wanx-v1";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String generateImage(String prompt, String size, String style) {
        String endpoint = getDashScopeCompatibleBaseUrl() + "/images/generations";
        String requestBody = null;
        HttpResponse<String> response = null;
        String responseBody = null;
        String imageUrl = null;
        String error = null;
        Map<String, String> headers = AiTraceSupport.jsonHeaders(apiKey);
        String resolvedSize = normalizeImageSize(size);
        try {
            // DashScope uses OpenAI-compatible /images/generations endpoint
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("n", 1);
            body.put("size", convertSize(resolvedSize));
            if (style != null && !style.isBlank()) {
                body.put("style", style);
            }

            requestBody = objectMapper.writeValueAsString(body);
            log.info("DashScope image request endpoint={}, model={}", endpoint, model);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            responseBody = response.body();

            if (response.statusCode() >= 400) {
                if (response.statusCode() == 404 && isQwenImageModel(model)) {
                    AiTraceSupport.record(
                            "image",
                            "dashscope",
                            "generate_image",
                            "POST",
                            endpoint,
                            headers,
                            requestBody,
                            response.statusCode(),
                            response.headers().firstValue("Content-Type").orElse(null),
                            responseBody,
                            responseBody.length(),
                            false,
                            null,
                            "HTTP 404 fallback to multimodal endpoint");
                    log.warn("DashScope compatible image endpoint returned 404 for model {}, fallback to multimodal text-only", model);
                    return generateImageByMultimodal(prompt, resolvedSize, style, List.of());
                }
                log.error("DashScope image generation API error: {} - {}", response.statusCode(), responseBody);
                error = "HTTP " + response.statusCode() + " - " + responseBody;
                throw new RuntimeException("Image generation failed: " + error);
            }

            JsonNode responseJson = objectMapper.readTree(responseBody);
            imageUrl = extractImageUrl(responseJson);
            return imageUrl;
        } catch (RuntimeException e) {
            if (!hasText(error)) {
                error = e.getMessage();
            }
            throw e;
        } catch (Exception e) {
            if (!hasText(error)) {
                error = e.getMessage();
            }
            log.error("DashScope image generation failed", e);
            throw new RuntimeException("Image generation failed: " + e.getMessage());
        } finally {
            AiTraceSupport.record(
                    "image",
                    "dashscope",
                    "generate_image",
                    "POST",
                    endpoint,
                    headers,
                    requestBody,
                    response != null ? response.statusCode() : null,
                    response != null ? response.headers().firstValue("Content-Type").orElse(null) : null,
                    responseBody,
                    responseBody != null ? responseBody.length() : null,
                    hasText(imageUrl),
                    imageUrl,
                    error);
        }
    }

    @Override
    public String generateImage(String prompt, String size, String style, List<String> referenceImageUrls) {
        String resolvedSize = normalizeImageSize(size);
        List<String> references = normalizeReferenceImageUrls(referenceImageUrls);
        if (isQwenImageModel(model)) {
            if (!references.isEmpty()) {
                log.info("Ignore {} reference images for model {} and force text-to-image endpoint", references.size(), model);
            }
            return generateImage(prompt, resolvedSize, style);
        }
        if (references.isEmpty()) {
            return generateImage(prompt, resolvedSize, style);
        }
        try {
            return generateImageWithReferences(prompt, resolvedSize, style, references);
        } catch (Exception e) {
            log.warn("DashScope reference-image generation failed, falling back to text-to-image: {}", e.getMessage());
            return generateImage(prompt, resolvedSize, style);
        }
    }

    /**
     * Poll for async task completion.
     */
    private String pollTaskResult(String taskId) throws Exception {
        String taskUrl = getDashScopeApiBaseUrl() + "/tasks/" + taskId;

        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            Thread.sleep(POLL_INTERVAL_MS);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(taskUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    String responseBody = response.body();
                    AiTraceSupport.record(
                        "image",
                        "dashscope",
                        "poll_image_task",
                        "GET",
                        taskUrl,
                        Map.of("Authorization", AiTraceSupport.maskBearer(apiKey)),
                        null,
                        response.statusCode(),
                        response.headers().firstValue("Content-Type").orElse(null),
                        responseBody,
                        responseBody.length(),
                        response.statusCode() < 400,
                        null,
                        response.statusCode() >= 400 ? ("HTTP " + response.statusCode()) : null);
                    JsonNode json = objectMapper.readTree(responseBody);
            JsonNode output = json.path("output");
            String status = output.path("task_status").asText("");

            switch (status) {
                case "SUCCEEDED" -> {
                    JsonNode results = output.path("results");
                    if (results.isArray() && results.size() > 0) {
                        return results.get(0).path("url").asText();
                    }
                    throw new RuntimeException("Task succeeded but no image URL found");
                }
                case "FAILED" -> throw new RuntimeException("Image generation task failed: " +
                        output.path("message").asText("unknown error"));
                default -> log.debug("Task {} status: {}, attempt {}/{}", taskId, status, i + 1, MAX_POLL_ATTEMPTS);
            }
        }

        throw new RuntimeException("Image generation task timed out after " +
                (MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000) + " seconds");
    }

    private String generateImageWithReferences(String prompt, String size, String style, List<String> referenceImageUrls)
            throws Exception {
        return generateImageByMultimodal(prompt, size, style, referenceImageUrls);
        }

        private String generateImageByMultimodal(String prompt, String size, String style, List<String> referenceImageUrls)
            throws Exception {
        String resolvedSize = normalizeImageSize(size);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", resolveReferenceEditModel());

        ObjectNode input = body.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        ArrayNode content = userMessage.putArray("content");
        for (String referenceImageUrl : referenceImageUrls) {
            content.addObject().put("image", referenceImageUrl);
        }
        content.addObject().put("text", buildReferencePrompt(prompt, resolvedSize, style));

        String endpoint = getDashScopeApiBaseUrl() + "/services/aigc/multimodal-conversation/generation";
        log.info("DashScope multimodal image request endpoint={}, model={}, refCount={}", endpoint, model, referenceImageUrls.size());

    String requestBody = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(180))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    String responseBody = response.body();
    String imageUrl = null;
    String error = null;
        if (response.statusCode() >= 400) {
        error = "HTTP " + response.statusCode() + " - " + responseBody;
        AiTraceSupport.record(
            "image",
            "dashscope",
            "generate_image_multimodal",
            "POST",
            endpoint,
            AiTraceSupport.jsonHeaders(apiKey),
            requestBody,
            response.statusCode(),
            response.headers().firstValue("Content-Type").orElse(null),
            responseBody,
            responseBody.length(),
            false,
            null,
            error);
        throw new RuntimeException(error);
        }
    JsonNode responseJson = objectMapper.readTree(responseBody);
    imageUrl = extractImageUrl(responseJson);
    AiTraceSupport.record(
        "image",
        "dashscope",
        "generate_image_multimodal",
        "POST",
        endpoint,
        AiTraceSupport.jsonHeaders(apiKey),
        requestBody,
        response.statusCode(),
        response.headers().firstValue("Content-Type").orElse(null),
        responseBody,
        responseBody.length(),
        hasText(imageUrl),
        imageUrl,
        null);
    return imageUrl;
    }

    private String extractImageUrl(JsonNode responseJson) throws Exception {
        // OpenAI-compatible response
        JsonNode dataNode = responseJson.path("data");
        if (dataNode.isArray() && dataNode.size() > 0) {
            String url = textValue(dataNode.get(0).path("url"));
            if (hasText(url)) {
                return url;
            }
        }

        // DashScope async task response
        JsonNode outputNode = responseJson.path("output");
        if (!outputNode.isMissingNode()) {
            String directOutputUrl = findImageUrl(outputNode);
            if (hasText(directOutputUrl)) {
                return directOutputUrl;
            }
            String taskId = textValue(outputNode.path("task_id"));
            if (hasText(taskId)) {
                return pollTaskResult(taskId);
            }
        }

        // Multimodal conversation response
        JsonNode choicesNode = responseJson.path("output").path("choices");
        if (choicesNode.isArray()) {
            for (JsonNode choiceNode : choicesNode) {
                String url = findImageUrl(choiceNode);
                if (hasText(url)) {
                    return url;
                }
            }
        }

        String rootLevelUrl = findImageUrl(responseJson);
        if (hasText(rootLevelUrl)) {
            return rootLevelUrl;
        }

        throw new RuntimeException("Unexpected DashScope response format: " + responseJson);
    }

    private String findImageUrl(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            String image = textValue(node.path("image"));
            if (hasText(image) && isHttpUrl(image)) {
                return image;
            }
            String imageUrl = textValue(node.path("image_url"));
            if (hasText(imageUrl) && isHttpUrl(imageUrl)) {
                return imageUrl;
            }
            String url = textValue(node.path("url"));
            if (hasText(url) && isHttpUrl(url)) {
                return url;
            }
            for (String fieldName : List.of("results", "result", "content", "message", "messages", "output")) {
                String nested = findImageUrl(node.path(fieldName));
                if (hasText(nested)) {
                    return nested;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String nested = findImageUrl(item);
                if (hasText(nested)) {
                    return nested;
                }
            }
        }
        return null;
    }

    private List<String> normalizeReferenceImageUrls(List<String> referenceImageUrls) {
        Set<String> normalized = new LinkedHashSet<>();
        if (referenceImageUrls == null) {
            return new ArrayList<>();
        }
        for (String referenceImageUrl : referenceImageUrls) {
            if (hasText(referenceImageUrl) && isHttpUrl(referenceImageUrl)) {
                normalized.add(referenceImageUrl);
            }
        }
        return new ArrayList<>(normalized);
    }

    private String resolveReferenceEditModel() {
        if (hasText(model) && (model.contains("image-edit") || model.startsWith("qwen-image"))) {
            return model;
        }
        return DEFAULT_REFERENCE_EDIT_MODEL;
    }

    private String buildReferencePrompt(String prompt, String size, String style) {
        StringBuilder builder = new StringBuilder();
        if (hasText(prompt)) {
            builder.append(prompt.trim());
        } else {
            builder.append("请基于参考图生成符合短剧分镜需求的画面");
        }
        if (hasText(style)) {
            builder.append("，风格要求：").append(style);
        }
        if (hasText(size)) {
            builder.append("，输出尺寸：").append(convertSize(size));
        }
        builder.append("，请保持参考人物与场景的一致性，并输出最终图片。");
        return builder.toString();
    }

    private String getDashScopeApiBaseUrl() {
        return getDashScopeRootBaseUrl() + "/api/v1";
    }

    private String getDashScopeCompatibleBaseUrl() {
        return getDashScopeRootBaseUrl() + "/compatible-mode/v1";
    }

    private String getDashScopeRootBaseUrl() {
        String normalizedBaseUrl = baseUrl;
        if (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }

        String[] knownSuffixes = new String[] {
                "/images/generations",
                "/services/aigc/multimodal-conversation/generation",
                "/compatible-mode/v1",
                "/api/v1"
        };

        boolean stripped;
        do {
            stripped = false;
            for (String suffix : knownSuffixes) {
                if (normalizedBaseUrl.endsWith(suffix)) {
                    normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - suffix.length());
                    stripped = true;
                    break;
                }
            }
            if (normalizedBaseUrl.endsWith("/")) {
                normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
            }
        } while (stripped);

        return normalizedBaseUrl;
    }

    private boolean isQwenImageModel(String modelName) {
        return hasText(modelName) && modelName.toLowerCase(Locale.ROOT).startsWith("qwen-image");
    }

    private boolean isDashScopeUrlParameterError(Exception e) {
        if (e == null || !hasText(e.getMessage())) {
            return false;
        }
        String message = e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("url error") || (message.contains("invalidparameter") && message.contains("url"));
    }

    /**
     * Convert standard sizes to DashScope supported sizes.
     */
    private String convertSize(String size) {
        // DashScope supports: 1024x1024, 720x1280, 1280x720
        return switch (size) {
            case "1024x1792", "768x1344" -> "720x1280";
            case "1792x1024", "1344x768" -> "1280x720";
            default -> "1024x1024";
        };
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return hasText(value) ? value : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) || !hasText(host)) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if ("localhost".equals(normalizedHost)
                    || normalizedHost.startsWith("127.")
                    || "0.0.0.0".equals(normalizedHost)
                    || "::1".equals(normalizedHost)) {
                return false;
            }
            return !normalizedHost.startsWith("10.")
                    && !normalizedHost.startsWith("169.254.")
                    && !normalizedHost.startsWith("192.168.")
                    && !normalizedHost.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..*")
                    && !normalizedHost.startsWith("fc")
                    && !normalizedHost.startsWith("fd")
                    && !normalizedHost.startsWith("fe8")
                    && !normalizedHost.startsWith("fe9")
                    && !normalizedHost.startsWith("fea")
                    && !normalizedHost.startsWith("feb");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
