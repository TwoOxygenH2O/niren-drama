package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.ImageAiProvider;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Slf4j
public class ExternalImageProvider implements ImageAiProvider {

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ExternalImageProvider(String endpoint, String apiKey, String model) {
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

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(180));

            if (hasText(apiKey)) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("HTTP " + response.statusCode() + " - " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String imageUrl = extractImageUrl(json);
            if (!hasText(imageUrl)) {
                throw new RuntimeException("External image API returned empty url: " + response.body());
            }
            return imageUrl;
        } catch (Exception e) {
            log.error("External image generation failed", e);
            throw new RuntimeException("Image generation failed: " + e.getMessage(), e);
        }
    }

    private String normalizeEndpoint(String baseUrl) {
        if (!hasText(baseUrl)) {
            throw new RuntimeException("External image endpoint is required");
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/images/generations")) {
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
