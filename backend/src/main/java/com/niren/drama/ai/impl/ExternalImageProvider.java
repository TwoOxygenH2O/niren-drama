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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class ExternalImageProvider implements ImageAiProvider {

    private final String providerName;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final String uploadPath;
    private final String publicBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ExternalImageProvider(String endpoint, String apiKey, String model) {
        this(endpoint, apiKey, model, "custom", null, null);
    }

    public ExternalImageProvider(String endpoint, String apiKey, String model, String providerName) {
        this(endpoint, apiKey, model, providerName, null, null);
    }

    public ExternalImageProvider(String endpoint,
                                 String apiKey,
                                 String model,
                                 String providerName,
                                 String uploadPath,
                                 String publicBaseUrl) {
        this.providerName = hasText(providerName) ? providerName : "custom";
        this.endpoint = normalizeEndpoint(endpoint);
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
                imageUrl = persistBase64Image(json);
            }
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

    private String persistBase64Image(JsonNode root) throws Exception {
        String base64Payload = extractBase64Image(root);
        if (!hasText(base64Payload)) {
            return null;
        }

        Base64ImageData imageData = decodeBase64Image(base64Payload);
        if (imageData.bytes().length == 0) {
            return null;
        }

        if (!hasText(uploadPath) || !hasText(publicBaseUrl)) {
            log.debug("External image API returned base64 payload but upload path/base URL is not configured, returning data URL directly");
            return buildDataUrl(imageData);
        }

        Path imageDir = Paths.get(uploadPath, "generated-images");
        Files.createDirectories(imageDir);
        String extension = resolveExtension(imageData);
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path imageFile = imageDir.resolve(fileName);
        Files.write(imageFile, imageData.bytes());

        String savedUrl = normalizePublicBaseUrl(publicBaseUrl) + "/generated-images/" + fileName;
        log.debug("External image API returned base64 payload, saved generated image to {}", savedUrl);
        return savedUrl;
    }

    private String extractBase64Image(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }

        JsonNode data = root.path("data");
        if (data.isArray() && data.size() > 0) {
            String payload = firstText(
                    data.get(0).path("b64_json"),
                    data.get(0).path("b64"),
                    data.get(0).path("base64"),
                    data.get(0).path("image_base64"));
            if (hasText(payload)) {
                return payload;
            }
        }

        JsonNode outputResults = root.path("output").path("results");
        if (outputResults.isArray() && outputResults.size() > 0) {
            String payload = firstText(
                    outputResults.get(0).path("b64_json"),
                    outputResults.get(0).path("b64"),
                    outputResults.get(0).path("base64"),
                    outputResults.get(0).path("image_base64"));
            if (hasText(payload)) {
                return payload;
            }
        }

        return firstText(
                root.path("b64_json"),
                root.path("b64"),
                root.path("base64"),
                root.path("image_base64"),
                root.path("result").path("b64_json"),
                root.path("result").path("b64"),
                root.path("result").path("base64"),
                root.path("output").path("b64_json"),
                root.path("output").path("base64"));
    }

    private Base64ImageData decodeBase64Image(String payload) {
        String normalized = payload.trim();
        String mediaType = "image/png";
        if (normalized.startsWith("data:")) {
            int commaIndex = normalized.indexOf(',');
            if (commaIndex > 5) {
                String header = normalized.substring(5, commaIndex);
                int semicolonIndex = header.indexOf(';');
                mediaType = semicolonIndex > 0 ? header.substring(0, semicolonIndex) : header;
                normalized = normalized.substring(commaIndex + 1);
            }
        }
        byte[] bytes = Base64.getDecoder().decode(normalized);
        return new Base64ImageData(mediaType, bytes);
    }

    private String buildDataUrl(Base64ImageData imageData) {
        return "data:" + imageData.mediaType() + ";base64," + Base64.getEncoder().encodeToString(imageData.bytes());
    }

    private String resolveExtension(Base64ImageData imageData) {
        String mediaType = imageData.mediaType();
        if (hasText(mediaType)) {
            if (mediaType.contains("png")) {
                return "png";
            }
            if (mediaType.contains("jpeg") || mediaType.contains("jpg")) {
                return "jpg";
            }
            if (mediaType.contains("webp")) {
                return "webp";
            }
            if (mediaType.contains("gif")) {
                return "gif";
            }
        }

        byte[] bytes = imageData.bytes();
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47) {
            return "png";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "webp";
        }
        return "png";
    }

    private String normalizePublicBaseUrl(String value) {
        String normalized = value.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
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

    private record Base64ImageData(String mediaType, byte[] bytes) {}

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
