package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.ImageAiProvider;
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
public class ExternalImageProvider implements ImageAiProvider {

    private final String providerName;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ExternalImageProvider(String endpoint, String apiKey, String model) {
        this(endpoint, apiKey, model, "custom");
    }

    public ExternalImageProvider(String endpoint, String apiKey, String model, String providerName) {
        this.providerName = hasText(providerName) ? providerName : "custom";
        this.endpoint = normalizeEndpoint(endpoint);
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String generateImage(String prompt, String size, String style) {
        return generateImage(prompt, size, style, List.of());
    }

    @Override
    public String generateImage(String prompt, String size, String style, List<String> referenceImageUrls) {
        String requestBody = null;
        HttpResponse<String> response = null;
        String responseBody = null;
        String imageUrl = null;
        String error = null;
        Map<String, String> headers = AiTraceSupport.jsonHeaders(apiKey);
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", hasText(model) ? model : "qwen-image-2.0-pro");
            body.put("prompt", prompt);
            body.put("size", hasText(size) ? size : "1024x1792");
            if (hasText(style)) {
                body.put("style", style);
            }

            if (referenceImageUrls != null && !referenceImageUrls.isEmpty()) {
                ArrayNode refs = body.putArray("referenceImageUrls");
                for (String url : referenceImageUrls) {
                    if (hasText(url)) {
                        refs.add(url);
                    }
                }
            }

            requestBody = objectMapper.writeValueAsString(body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(180));

            if (hasText(apiKey)) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            responseBody = response.body();
            if (response.statusCode() >= 400) {
                error = "HTTP " + response.statusCode() + " - " + responseBody;
                throw new RuntimeException(error);
            }

            JsonNode json = objectMapper.readTree(responseBody);
            imageUrl = extractImageUrl(json);
            if (!hasText(imageUrl)) {
                error = "External image API returned empty url: " + responseBody;
                throw new RuntimeException(error);
            }
            return imageUrl;
        } catch (Exception e) {
            if (!hasText(error)) {
                error = e.getMessage();
            }
            log.error("External image generation failed", e);
            throw new RuntimeException("Image generation failed: " + e.getMessage(), e);
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

    private String normalizeEndpoint(String baseUrl) {
        if (!hasText(baseUrl)) {
            throw new RuntimeException("External image endpoint is required");
        }
        String normalized = baseUrl.trim();
        if (normalized.contains("/images/")
                || normalized.contains("/services/")
                || normalized.endsWith("/generation")
                || normalized.endsWith("/generations")) {
            return normalized;
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized + "/images/generations";
    }

    private String extractImageUrl(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }

        JsonNode data = root.path("data");
        if (data.isArray() && data.size() > 0) {
            String url = data.get(0).path("url").asText(null);
            if (hasText(url)) {
                return url;
            }
        }

        String url = root.path("url").asText(null);
        if (hasText(url)) {
            return url;
        }

        String resultUrl = root.path("result").path("url").asText(null);
        if (hasText(resultUrl)) {
            return resultUrl;
        }

        JsonNode outputResults = root.path("output").path("results");
        if (outputResults.isArray() && outputResults.size() > 0) {
            String outputUrl = outputResults.get(0).path("url").asText(null);
            if (hasText(outputUrl)) {
                return outputUrl;
            }
        }

        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
