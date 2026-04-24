package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.ImageAiProvider;
import com.niren.drama.ai.RemoteAssetStorage;
import com.niren.drama.ai.trace.AiTraceSupport;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class AliyunImageProvider implements ImageAiProvider {

    private static final int MAX_POLL_ATTEMPTS = 60;
    private static final long POLL_INTERVAL_MS = 3000L;

    private final String providerName;
    private final String apiBaseUrl;
    private final String apiKey;
    private final String model;
    private final String uploadPath;
    private final String publicBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AliyunImageProvider(String baseUrl, String apiKey, String model, String providerName) {
        this(baseUrl, apiKey, model, providerName, null, null);
    }

    public AliyunImageProvider(String baseUrl,
                               String apiKey,
                               String model,
                               String providerName,
                               String uploadPath,
                               String publicBaseUrl) {
        this.providerName = hasText(providerName) ? providerName : "aliyun";
        this.apiBaseUrl = normalizeApiBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = hasText(model) ? model : "qwen-image-2.0-pro";
        this.uploadPath = uploadPath;
        this.publicBaseUrl = publicBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String generateImage(String prompt, String size, String style) {
        return generateImage(prompt, size, style, List.of(), null);
    }

    @Override
    public String generateImage(String prompt, String size, String style, List<String> referenceImageUrls) {
        return generateImage(prompt, size, style, referenceImageUrls, null);
    }

    @Override
    public String generateImage(String prompt, String size, String style, List<String> referenceImageUrls, String negativePrompt) {
        String endpoint = apiBaseUrl + "/services/aigc/multimodal-generation/generation";
        String requestBody = null;
        HttpResponse<String> response = null;
        String responseBody = null;
        String imageUrl = null;
        String error = null;
        Map<String, String> headers = AiTraceSupport.jsonHeaders(apiKey);
        String resolvedSize = normalizeImageSize(size);

        try {
            ObjectNode body = buildRequestBody(prompt, resolvedSize, style, referenceImageUrls, negativePrompt);
            requestBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(180))
                    .build();

            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            responseBody = response.body();
            if (response.statusCode() >= 400) {
                error = "HTTP " + response.statusCode() + " - " + responseBody;
                throw new RuntimeException(error);
            }

            JsonNode root = objectMapper.readTree(responseBody);
            imageUrl = extractImageUrl(root);
            if (!hasText(imageUrl)) {
                error = "阿里云生图接口未返回有效图片地址: " + responseBody;
                throw new RuntimeException(error);
            }
            imageUrl = persistImageUrl(imageUrl);
            return imageUrl;
        } catch (Exception e) {
            if (!hasText(error)) {
                error = e.getMessage();
            }
            log.error("Aliyun image generation failed", e);
            throw new RuntimeException("Image generation failed: " + error, e);
        } finally {
            AiTraceSupport.record(
                    "image",
                    providerName,
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

    private ObjectNode buildRequestBody(String prompt,
                                        String size,
                                        String style,
                                        List<String> referenceImageUrls,
                                        String negativePrompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);

        ObjectNode input = body.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        ArrayNode content = userMessage.putArray("content");

        for (String referenceImageUrl : normalizeReferenceImageUrls(referenceImageUrls)) {
            content.addObject().put("image", referenceImageUrl);
        }
        content.addObject().put("text", buildPrompt(prompt, size, style));

        ObjectNode parameters = body.putObject("parameters");
        parameters.put("n", 1);
        parameters.put("negative_prompt", hasText(negativePrompt) ? negativePrompt : "");
        parameters.put("watermark", false);
        return body;
    }

    private String extractImageUrl(JsonNode root) throws Exception {
        String directUrl = extractDirectImageUrl(root);
        if (hasText(directUrl)) {
            return directUrl;
        }

        String taskId = firstText(
                root.path("output").path("task_id"),
                root.path("output").path("taskId"),
                root.path("task_id"),
                root.path("taskId"));
        if (hasText(taskId)) {
            return pollTaskResult(taskId);
        }
        return null;
    }

    private String pollTaskResult(String taskId) throws Exception {
        String taskUrl = apiBaseUrl + "/tasks/" + taskId;
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Thread.sleep(POLL_INTERVAL_MS);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(taskUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();
            JsonNode root = objectMapper.readTree(responseBody);
                String imageUrl = extractDirectImageUrl(root);
            String status = firstText(root.path("output").path("task_status"), root.path("task_status"));
            String error = response.statusCode() >= 400 ? ("HTTP " + response.statusCode() + " - " + responseBody) : null;

            AiTraceSupport.record(
                    "image",
                    providerName,
                    "poll_image_task",
                    "GET",
                    taskUrl,
                    Map.of("Authorization", AiTraceSupport.maskBearer(apiKey)),
                    null,
                    response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(null),
                    responseBody,
                    responseBody != null ? responseBody.length() : null,
                    response.statusCode() < 400,
                    imageUrl,
                    error);

            if (response.statusCode() >= 400) {
                throw new RuntimeException(error);
            }
            if (hasText(imageUrl)) {
                return imageUrl;
            }
            if ("FAILED".equalsIgnoreCase(status)) {
                throw new RuntimeException(firstText(root.path("output").path("message"), root.path("message")));
            }
        }
        throw new RuntimeException("阿里云生图任务轮询超时");
    }

    private String extractDirectImageUrl(JsonNode root) {
        String directUrl = firstText(
                root.path("output").path("result").path("url"),
                root.path("output").path("result").path("image"),
                root.path("output").path("results").isArray() && root.path("output").path("results").size() > 0
                        ? root.path("output").path("results").get(0).path("url") : null,
                root.path("output").path("results").isArray() && root.path("output").path("results").size() > 0
                        ? root.path("output").path("results").get(0).path("image") : null,
                root.path("result").path("url"),
                root.path("result").path("image"),
                root.path("data").path("url"),
                root.path("data").path("image"),
                root.path("url"),
                root.path("image"));
        if (hasText(directUrl)) {
            return directUrl;
        }

        JsonNode choices = root.path("output").path("choices");
        if (choices.isArray()) {
            for (JsonNode choice : choices) {
                JsonNode content = choice.path("message").path("content");
                if (!content.isArray()) {
                    continue;
                }
                for (JsonNode item : content) {
                    String contentImage = firstText(item.path("image"), item.path("url"));
                    if (hasText(contentImage)) {
                        return contentImage;
                    }
                }
            }
        }
        return null;
    }

    private String buildPrompt(String prompt, String size, String style) {
        StringBuilder builder = new StringBuilder();
        builder.append(hasText(prompt) ? prompt.trim() : "请生成适合短剧竖屏分镜的高质量画面");
        if (hasText(size)) {
            builder.append(" 输出尺寸倾向：").append(size).append('。');
        }
        if (hasText(style)) {
            builder.append(" 风格要求：").append(style).append('。');
        }
        return builder.toString();
    }

    private List<String> normalizeReferenceImageUrls(List<String> referenceImageUrls) {
        Set<String> normalized = new LinkedHashSet<>();
        if (referenceImageUrls == null) {
            return List.of();
        }
        for (String referenceImageUrl : referenceImageUrls) {
            if (hasText(referenceImageUrl) && (referenceImageUrl.startsWith("http://") || referenceImageUrl.startsWith("https://"))) {
                normalized.add(referenceImageUrl);
            }
        }
        return List.copyOf(normalized);
    }

    private String normalizeApiBaseUrl(String value) {
        if (!hasText(value)) {
            return "https://dashscope.aliyuncs.com/api/v1";
        }
        String normalized = trimTrailingSlash(value.trim());
        String[] suffixes = new String[] {
                "/services/aigc/multimodal-generation/generation",
                "/images/generations",
                "/compatible-mode/v1",
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

    private String firstText(JsonNode... nodes) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            String text = node.asText(null);
            if (hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String persistImageUrl(String imageUrl) {
        return RemoteAssetStorage.persistHttpUrl(imageUrl, uploadPath, publicBaseUrl, "generated-images", httpClient, "png");
    }
}