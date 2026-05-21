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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class ComfyUiImageProvider implements ImageAiProvider {

    private static final int MAX_POLL_ATTEMPTS = 120;
    private static final long POLL_INTERVAL_MS = 2000L;

    private final String apiBaseUrl;
    private final String apiKey;
    private final String model;
    private final String uploadPath;
    private final String publicBaseUrl;
    private final String extra;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ComfyUiImageProvider(String baseUrl, String apiKey, String model, String extra,
                                String uploadPath, String publicBaseUrl) {
        this.apiBaseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = hasText(model) ? model : "";
        this.extra = extra;
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
    public String generateImage(String prompt, String size, String style,
                                 List<String> referenceImageUrls, String negativePrompt) {
        String promptEndpoint = apiBaseUrl + "/prompt";
        String requestBody = null;
        HttpResponse<String> response = null;
        String responseBody = null;
        String imageUrl = null;
        String error = null;
        Map<String, String> headers = AiTraceSupport.jsonHeaders(apiKey);
        String resolvedSize = normalizeImageSize(size);

        try {
            ObjectNode workflow = buildWorkflow(prompt, resolvedSize, style, negativePrompt);
            ObjectNode body = objectMapper.createObjectNode();
            body.set("prompt", workflow);
            requestBody = objectMapper.writeValueAsString(body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(promptEndpoint))
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

            JsonNode root = objectMapper.readTree(responseBody);
            String promptId = root.path("prompt_id").asText(null);
            if (!hasText(promptId)) {
                error = "ComfyUI 未返回 prompt_id: " + responseBody;
                throw new RuntimeException(error);
            }

            log.info("ComfyUI 图片生成任务已提交, prompt_id={}", promptId);
            String outputFilename = pollForResult(promptId);
            imageUrl = apiBaseUrl + "/view?filename=" + outputFilename + "&type=output";
            imageUrl = RemoteAssetStorage.persistHttpUrl(imageUrl, uploadPath, publicBaseUrl,
                    "generated-images", httpClient, "png");

            return imageUrl;
        } catch (Exception e) {
            if (!hasText(error)) {
                error = e.getMessage();
            }
            log.error("ComfyUI 图片生成失败", e);
            throw new RuntimeException("Image generation failed: " + error, e);
        } finally {
            AiTraceSupport.record(
                    "image",
                    "comfyui",
                    "generate_image",
                    "POST",
                    promptEndpoint,
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

    private ObjectNode buildWorkflow(String prompt, String size, String style, String negativePrompt) {
        String userExtra = null;
        String workflowFile = null;
        if (hasText(extra)) {
            try {
                JsonNode extraJson = objectMapper.readTree(extra);
                JsonNode workflowNode = extraJson.path("workflow");
                if (workflowNode.isObject()) {
                    return (ObjectNode) workflowNode.deepCopy();
                }
                workflowFile = extraJson.path("workflowFile").asText(null);
                userExtra = extraJson.path("extraPrompt").asText(null);
            } catch (Exception ignored) {
            }
        }

        int width = 1024;
        int height = 1024;
        if (hasText(size)) {
            String[] parts = size.toLowerCase().split("x");
            if (parts.length == 2) {
                try {
                    width = Integer.parseInt(parts[0].trim());
                    height = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        String fullPrompt = prompt;
        if (hasText(style)) {
            fullPrompt = prompt + ", " + style;
        }
        if (hasText(userExtra)) {
            fullPrompt = fullPrompt + ", " + userExtra;
        }

        String negPrompt = hasText(negativePrompt)
                ? negativePrompt
                : "low quality, blurry, distorted, deformed, ugly, watermark, text";

        // Try loading workflow template: explicit name → loadWorkflow; no name → loadDefaultWorkflow (user's ComfyUI first)
        ObjectNode template;
        if (hasText(workflowFile)) {
            template = ComfyUiWorkflowLoader.loadWorkflow(apiBaseUrl, httpClient, workflowFile);
        } else {
            template = ComfyUiWorkflowLoader.loadDefaultWorkflow(apiBaseUrl, httpClient, "image_z_image_turbo.json", "image");
        }
        if (template != null) {
            ComfyUiWorkflowLoader.injectPrompt(template, fullPrompt);
            return template;
        }

        // Fallback to programmatic SDXL workflow
        String checkpointModel = resolveCheckpointModel();
        return buildSdxlWorkflow(fullPrompt, negPrompt, checkpointModel, width, height);
    }

    private ObjectNode buildSdxlWorkflow(String prompt, String negativePrompt,
                                          String checkpointModel, int width, int height) {
        ObjectNode workflow = objectMapper.createObjectNode();

        // Node 1: CheckpointLoaderSimple
        ObjectNode loader = workflow.putObject("1");
        loader.put("class_type", "CheckpointLoaderSimple");
        ObjectNode loaderInputs = loader.putObject("inputs");
        loaderInputs.put("ckpt_name", checkpointModel);

        // Node 2: CLIPTextEncode (positive prompt)
        ObjectNode posClip = workflow.putObject("2");
        posClip.put("class_type", "CLIPTextEncode");
        ObjectNode posClipInputs = posClip.putObject("inputs");
        posClipInputs.put("text", prompt);
        ArrayNode posClipFrom = posClipInputs.putArray("clip");
        posClipFrom.add("1");
        posClipFrom.add(1);

        // Node 3: CLIPTextEncode (negative prompt)
        ObjectNode negClip = workflow.putObject("3");
        negClip.put("class_type", "CLIPTextEncode");
        ObjectNode negClipInputs = negClip.putObject("inputs");
        negClipInputs.put("text", negativePrompt);
        ArrayNode negClipFrom = negClipInputs.putArray("clip");
        negClipFrom.add("1");
        negClipFrom.add(1);

        // Node 4: EmptyLatentImage
        ObjectNode latent = workflow.putObject("4");
        latent.put("class_type", "EmptyLatentImage");
        ObjectNode latentInputs = latent.putObject("inputs");
        latentInputs.put("width", width);
        latentInputs.put("height", height);
        latentInputs.put("batch_size", 1);

        // Node 5: KSampler
        ObjectNode sampler = workflow.putObject("5");
        sampler.put("class_type", "KSampler");
        ObjectNode samplerInputs = sampler.putObject("inputs");
        samplerInputs.put("seed", UUID.randomUUID().hashCode() & Integer.MAX_VALUE);
        samplerInputs.put("steps", 25);
        samplerInputs.put("cfg", 7.0);
        samplerInputs.put("sampler_name", "euler_ancestral");
        samplerInputs.put("scheduler", "normal");
        samplerInputs.put("denoise", 1.0);
        ArrayNode modelFrom = samplerInputs.putArray("model");
        modelFrom.add("1");
        modelFrom.add(0);
        ArrayNode posFrom = samplerInputs.putArray("positive");
        posFrom.add("2");
        posFrom.add(0);
        ArrayNode negFrom = samplerInputs.putArray("negative");
        negFrom.add("3");
        negFrom.add(0);
        ArrayNode latentFrom = samplerInputs.putArray("latent_image");
        latentFrom.add("4");
        latentFrom.add(0);

        // Node 6: VAEDecode
        ObjectNode vaeDecode = workflow.putObject("6");
        vaeDecode.put("class_type", "VAEDecode");
        ObjectNode vaeDecodeInputs = vaeDecode.putObject("inputs");
        ArrayNode samplesFrom = vaeDecodeInputs.putArray("samples");
        samplesFrom.add("5");
        samplesFrom.add(0);
        ArrayNode vaeFrom = vaeDecodeInputs.putArray("vae");
        vaeFrom.add("1");
        vaeFrom.add(2);

        // Node 7: SaveImage
        ObjectNode saveImage = workflow.putObject("7");
        saveImage.put("class_type", "SaveImage");
        ObjectNode saveImageInputs = saveImage.putObject("inputs");
        saveImageInputs.put("filename_prefix", "niren");
        ArrayNode imagesFrom = saveImageInputs.putArray("images");
        imagesFrom.add("6");
        imagesFrom.add(0);

        return workflow;
    }

    private String pollForResult(String promptId) throws Exception {
        String historyUrl = apiBaseUrl + "/history/" + promptId;
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Thread.sleep(POLL_INTERVAL_MS);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(historyUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            if (response.statusCode() >= 400) {
                throw new RuntimeException("ComfyUI history 查询失败: HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode promptNode = root.path(promptId);
            if (promptNode.isMissingNode() || promptNode.isNull()) {
                continue;
            }

            JsonNode outputs = promptNode.path("outputs");
            if (outputs.isObject()) {
                for (var it = outputs.fields(); it.hasNext(); ) {
                    var entry = it.next();
                    JsonNode images = entry.getValue().path("images");
                    if (images.isArray() && images.size() > 0) {
                        String filename = images.get(0).path("filename").asText(null);
                        if (hasText(filename)) {
                            log.info("ComfyUI 图片生成完成, filename={}", filename);
                            return filename;
                        }
                    }
                }
            }

            JsonNode status = promptNode.path("status");
            if (status.isObject()) {
                String statusStr = status.path("status_str").asText("");
                if ("error".equalsIgnoreCase(statusStr)) {
                    JsonNode messages = status.path("messages");
                    String msg = messages.isArray() && messages.size() > 0
                            ? messages.toString() : "未知错误";
                    throw new RuntimeException("ComfyUI 任务执行失败: " + msg);
                }
            }
        }
        throw new RuntimeException("ComfyUI 图片生成轮询超时 (prompt_id=" + promptId + ")");
    }

    private String resolveCheckpointModel() {
        if (hasText(model)) {
            return model;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/object_info/CheckpointLoaderSimple"))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode ckptInput = root.path("CheckpointLoaderSimple")
                        .path("input")
                        .path("required")
                        .path("ckpt_name");
                if (ckptInput.isArray() && ckptInput.get(0).isArray() && ckptInput.get(0).size() > 0) {
                    String firstModel = ckptInput.get(0).get(0).asText(null);
                    if (hasText(firstModel)) {
                        log.info("ComfyUI 自动选择模型: {}", firstModel);
                        return firstModel;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("无法查询 ComfyUI 可用模型，使用默认值: {}", e.getMessage());
        }
        return "sd_xl_base_1.0.safetensors";
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!hasText(baseUrl)) {
            return "http://localhost:8188";
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
