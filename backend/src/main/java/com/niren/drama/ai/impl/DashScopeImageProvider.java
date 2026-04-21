package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.niren.drama.ai.ImageAiProvider;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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
        try {
            // DashScope uses OpenAI-compatible /images/generations endpoint
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("n", 1);
            body.put("size", size != null ? convertSize(size) : "1024x1024");
            if (style != null && !style.isBlank()) {
                body.put("style", style);
            }

            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/images/generations"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                log.error("DashScope image generation API error: {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("Image generation failed: HTTP " + response.statusCode());
            }

            JsonNode responseJson = objectMapper.readTree(response.body());

            // Check if response contains direct URL (synchronous response)
            JsonNode dataNode = responseJson.path("data");
            if (dataNode.isArray() && dataNode.size() > 0) {
                String url = dataNode.get(0).path("url").asText(null);
                if (url != null && !url.isBlank()) {
                    return url;
                }
            }

            // Check for async task response (DashScope native format)
            JsonNode outputNode = responseJson.path("output");
            if (!outputNode.isMissingNode()) {
                String taskId = outputNode.path("task_id").asText(null);
                if (taskId != null && !taskId.isBlank()) {
                    return pollTaskResult(taskId);
                }
            }

            throw new RuntimeException("Unexpected DashScope response format: " + response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("DashScope image generation failed", e);
            throw new RuntimeException("Image generation failed: " + e.getMessage());
        }
    }

    /**
     * Poll for async task completion.
     */
    private String pollTaskResult(String taskId) throws Exception {
        String taskUrl = baseUrl.replace("/compatible-mode/v1", "") + "/api/v1/tasks/" + taskId;

        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            Thread.sleep(POLL_INTERVAL_MS);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(taskUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(response.body());
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
}
