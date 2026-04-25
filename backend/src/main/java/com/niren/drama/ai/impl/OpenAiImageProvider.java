package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

@Slf4j
public class OpenAiImageProvider implements ImageAiProvider {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String uploadPath;
    private final String publicBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiImageProvider(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, null, null);
    }

    public OpenAiImageProvider(String baseUrl,
                               String apiKey,
                               String model,
                               String uploadPath,
                               String publicBaseUrl) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.uploadPath = uploadPath;
        this.publicBaseUrl = publicBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String generateImage(String prompt, String size, String style) {
        String endpoint = normalizeBaseUrl(baseUrl) + "/images/generations";
        String requestBody = null;
        HttpResponse<String> response = null;
        String responseBody = null;
        String imageUrl = null;
        String error = null;
        Map<String, String> headers = AiTraceSupport.jsonHeaders(apiKey);
        try {
            ObjectNode body = objectMapper.createObjectNode();
            String resolvedSize = normalizeImageSize(size);
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("n", 1);
            body.put("size", resolvedSize);
            if (style != null && !style.isBlank()) {
                body.put("style", style);
            }

            requestBody = objectMapper.writeValueAsString(body);
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
                error = "HTTP " + response.statusCode() + " - " + responseBody;
                throw new RuntimeException(error);
            }
            JsonNode responseJson = objectMapper.readTree(responseBody);
            imageUrl = extractImageUrl(responseJson);
            if (!hasText(imageUrl)) {
                error = "Image API returned empty url: " + responseBody;
                throw new RuntimeException(error);
            }
            imageUrl = RemoteAssetStorage.persistHttpUrl(imageUrl, uploadPath, publicBaseUrl, "generated-images", httpClient, "png");
            return imageUrl;
        } catch (Exception e) {
            if (!hasText(error)) {
                error = e.getMessage();
            }
            log.error("图片生成失败", e);
            throw new RuntimeException("Image generation failed: " + e.getMessage(), e);
        } finally {
            AiTraceSupport.record(
                    "image",
                    "openai",
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

    private String extractImageUrl(JsonNode responseJson) {
        JsonNode data = responseJson.path("data");
        if (data.isArray() && data.size() > 0) {
            return data.get(0).path("url").asText(null);
        }
        return responseJson.path("url").asText(null);
    }

    private String normalizeBaseUrl(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
