package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.RemoteAssetStorage;
import com.niren.drama.ai.VideoAiProvider;
import com.niren.drama.ai.trace.AiTraceSupport;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class ComfyUiVideoProvider implements VideoAiProvider {

    private static final int MAX_POLL_ATTEMPTS = 180;
    private static final long POLL_INTERVAL_MS = 3000L;

    private final String apiBaseUrl;
    private final String apiKey;
    private final String model;
    private final String uploadPath;
    private final String publicBaseUrl;
    private final String extra;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ComfyUiVideoProvider(String baseUrl, String apiKey, String model, String extra,
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
    public String generateVideoFromText(String prompt, int duration, String resolution,
                                         String quality, boolean withSound) {
        String promptEndpoint = apiBaseUrl + "/prompt";
        String requestBody = null;
        HttpResponse<String> response = null;
        String responseBody = null;
        String videoUrl = null;
        String error = null;
        Map<String, String> headers = AiTraceSupport.jsonHeaders(apiKey);

        try {
            int[] wh = parseResolution(resolution);
            int frames = Math.max(1, duration * 8);
            ObjectNode workflow = buildTextToVideoWorkflow(prompt, wh[0], wh[1], frames);
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

            log.info("ComfyUI 视频生成任务已提交 (text2video), prompt_id={}", promptId);
            String outputFilename = pollForResult(promptId);
            videoUrl = apiBaseUrl + "/view?filename=" + outputFilename + "&type=output";
            videoUrl = RemoteAssetStorage.persistHttpUrl(videoUrl, uploadPath, publicBaseUrl,
                    "generated-videos", httpClient, "mp4");

            return videoUrl;
        } catch (Exception e) {
            if (!hasText(error)) {
                error = e.getMessage();
            }
            log.error("ComfyUI 视频生成失败 (text2video)", e);
            throw new RuntimeException("Video generation failed: " + error, e);
        } finally {
            AiTraceSupport.record(
                    "video",
                    "comfyui",
                    "generate_video_text",
                    "POST",
                    promptEndpoint,
                    headers,
                    requestBody,
                    response != null ? response.statusCode() : null,
                    response != null ? response.headers().firstValue("Content-Type").orElse(null) : null,
                    responseBody,
                    responseBody != null ? responseBody.length() : null,
                    hasText(videoUrl),
                    videoUrl,
                    error);
        }
    }

    @Override
    public String generateVideoFromImage(String imageUrl, String prompt, int duration,
                                          String resolution, String quality, boolean withSound) {
        String promptEndpoint = apiBaseUrl + "/prompt";
        String requestBody = null;
        HttpResponse<String> response = null;
        String responseBody = null;
        String videoUrl = null;
        String error = null;
        Map<String, String> headers = AiTraceSupport.jsonHeaders(apiKey);

        try {
            int[] wh = parseResolution(resolution);
            int frames = Math.max(1, duration * 8);
            ObjectNode workflow = buildImageToVideoWorkflow(imageUrl, prompt, wh[0], wh[1], frames);
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

            log.info("ComfyUI 视频生成任务已提交 (image2video), prompt_id={}", promptId);
            String outputFilename = pollForResult(promptId);
            videoUrl = apiBaseUrl + "/view?filename=" + outputFilename + "&type=output";
            videoUrl = RemoteAssetStorage.persistHttpUrl(videoUrl, uploadPath, publicBaseUrl,
                    "generated-videos", httpClient, "mp4");

            return videoUrl;
        } catch (Exception e) {
            if (!hasText(error)) {
                error = e.getMessage();
            }
            log.error("ComfyUI 视频生成失败 (image2video)", e);
            throw new RuntimeException("Video generation failed: " + error, e);
        } finally {
            AiTraceSupport.record(
                    "video",
                    "comfyui",
                    "generate_video_image",
                    "POST",
                    promptEndpoint,
                    headers,
                    requestBody,
                    response != null ? response.statusCode() : null,
                    response != null ? response.headers().firstValue("Content-Type").orElse(null) : null,
                    responseBody,
                    responseBody != null ? responseBody.length() : null,
                    hasText(videoUrl),
                    videoUrl,
                    error);
        }
    }

    @Override
    public double estimateCost(int durationSeconds, String quality, boolean hasReferenceVideo, boolean withSound) {
        return 0;
    }

    private ObjectNode buildTextToVideoWorkflow(String prompt, int width, int height, int frames) {
        String workflowFile = null;
        if (hasText(extra)) {
            try {
                JsonNode extraJson = objectMapper.readTree(extra);
                JsonNode workflowNode = extraJson.path("workflow");
                if (workflowNode.isObject()) {
                    ObjectNode wf = (ObjectNode) workflowNode.deepCopy();
                    injectPromptIntoWorkflow(wf, prompt);
                    return wf;
                }
                workflowFile = extraJson.path("workflowFile").asText(null);
            } catch (Exception ignored) {
            }
        }

        // Try loading workflow template: explicit name → loadWorkflow; no name → loadDefaultWorkflow (user's ComfyUI first)
        ObjectNode template;
        if (hasText(workflowFile)) {
            template = ComfyUiWorkflowLoader.loadWorkflow(apiBaseUrl, httpClient, workflowFile);
        } else {
            template = ComfyUiWorkflowLoader.loadDefaultWorkflow(apiBaseUrl, httpClient, "video_ltx2_t2v_distilled.json", "video");
        }
        if (template != null) {
            ComfyUiWorkflowLoader.injectPrompt(template, prompt);
            return template;
        }

        return buildDefaultVideoWorkflow(prompt, null, width, height, frames);
    }

    private ObjectNode buildImageToVideoWorkflow(String imageUrl, String prompt,
                                                  int width, int height, int frames) {
        String workflowFile = null;
        if (hasText(extra)) {
            try {
                JsonNode extraJson = objectMapper.readTree(extra);
                JsonNode workflowNode = extraJson.path("workflow");
                if (workflowNode.isObject()) {
                    ObjectNode wf = (ObjectNode) workflowNode.deepCopy();
                    injectPromptIntoWorkflow(wf, prompt);
                    injectImageIntoWorkflow(wf, imageUrl);
                    return wf;
                }
                workflowFile = extraJson.path("workflowFile").asText(null);
            } catch (Exception ignored) {
            }
        }

        // Try loading workflow template: explicit name → loadWorkflow; no name → loadDefaultWorkflow (user's ComfyUI first)
        ObjectNode template;
        if (hasText(workflowFile)) {
            template = ComfyUiWorkflowLoader.loadWorkflow(apiBaseUrl, httpClient, workflowFile);
        } else {
            template = ComfyUiWorkflowLoader.loadDefaultWorkflow(apiBaseUrl, httpClient, "video_ltx2_i2v_distilled.json", "video");
        }
        if (template != null) {
            ComfyUiWorkflowLoader.injectPrompt(template, prompt);
            ComfyUiWorkflowLoader.injectImage(template, imageUrl);
            return template;
        }

        return buildDefaultVideoWorkflow(prompt, imageUrl, width, height, frames);
    }

    private ObjectNode buildDefaultVideoWorkflow(String prompt, String imageUrl,
                                                  int width, int height, int frames) {
        ObjectNode workflow = objectMapper.createObjectNode();

        // Node 1: Load checkpoint / model
        ObjectNode loader = workflow.putObject("1");
        loader.put("class_type", "CheckpointLoaderSimple");
        ObjectNode loaderInputs = loader.putObject("inputs");
        loaderInputs.put("ckpt_name", resolveCheckpointModel());

        // Node 2: CLIPTextEncode (positive prompt)
        ObjectNode posClip = workflow.putObject("2");
        posClip.put("class_type", "CLIPTextEncode");
        ObjectNode posClipInputs = posClip.putObject("inputs");
        posClipInputs.put("text", prompt);
        ArrayNode posClipFrom = posClipInputs.putArray("clip");
        posClipFrom.add("1");
        posClipFrom.add(1);

        // Node 3: EmptyLatentImage or image loader
        if (hasText(imageUrl)) {
            ObjectNode imgLoader = workflow.putObject("3");
            imgLoader.put("class_type", "LoadImage");
            ObjectNode imgLoaderInputs = imgLoader.putObject("inputs");
            imgLoaderInputs.put("image", imageUrl);
        } else {
            ObjectNode latent = workflow.putObject("3");
            latent.put("class_type", "EmptyLatentImage");
            ObjectNode latentInputs = latent.putObject("inputs");
            latentInputs.put("width", width);
            latentInputs.put("height", height);
            latentInputs.put("batch_size", frames);
        }

        // Node 4: KSampler
        ObjectNode sampler = workflow.putObject("4");
        sampler.put("class_type", "KSampler");
        ObjectNode samplerInputs = sampler.putObject("inputs");
        samplerInputs.put("seed", UUID.randomUUID().hashCode() & Integer.MAX_VALUE);
        samplerInputs.put("steps", 20);
        samplerInputs.put("cfg", 6.0);
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
        negFrom.add("2");
        negFrom.add(0);
        ArrayNode latentFrom = samplerInputs.putArray("latent_image");
        latentFrom.add("3");
        latentFrom.add(0);

        // Node 5: VAEDecode
        ObjectNode vaeDecode = workflow.putObject("5");
        vaeDecode.put("class_type", "VAEDecode");
        ObjectNode vaeDecodeInputs = vaeDecode.putObject("inputs");
        ArrayNode samplesFrom = vaeDecodeInputs.putArray("samples");
        samplesFrom.add("4");
        samplesFrom.add(0);
        ArrayNode vaeFrom = vaeDecodeInputs.putArray("vae");
        vaeFrom.add("1");
        vaeFrom.add(2);

        // Node 6: SaveVideo (VHS_VideoCombine or SaveImage as fallback)
        ObjectNode saveVideo = workflow.putObject("6");
        saveVideo.put("class_type", "VHS_VideoCombine");
        ObjectNode saveVideoInputs = saveVideo.putObject("inputs");
        saveVideoInputs.put("frame_rate", 8);
        saveVideoInputs.put("loop_count", 0);
        saveVideoInputs.put("filename_prefix", "niren_video");
        saveVideoInputs.put("format", "video/h264-mp4");
        ArrayNode imagesFrom = saveVideoInputs.putArray("images");
        imagesFrom.add("5");
        imagesFrom.add(0);

        return workflow;
    }

    private void injectPromptIntoWorkflow(ObjectNode workflow, String prompt) {
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (node.isObject()) {
                String classType = node.path("class_type").asText("");
                if ("CLIPTextEncode".equals(classType)) {
                    JsonNode inputs = node.path("inputs");
                    if (inputs.has("text")) {
                        ((ObjectNode) node.path("inputs")).put("text", prompt);
                        return;
                    }
                }
            }
        }
    }

    private void injectImageIntoWorkflow(ObjectNode workflow, String imageUrl) {
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (node.isObject()) {
                String classType = node.path("class_type").asText("");
                if ("LoadImage".equals(classType)) {
                    ((ObjectNode) node.path("inputs")).put("image", imageUrl);
                    return;
                }
            }
        }
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
                    JsonNode gifs = entry.getValue().path("gifs");
                    if (gifs.isArray() && gifs.size() > 0) {
                        String filename = gifs.get(0).path("filename").asText(null);
                        if (hasText(filename)) {
                            log.info("ComfyUI 视频生成完成, filename={}", filename);
                            return filename;
                        }
                    }
                    JsonNode videos = entry.getValue().path("videos");
                    if (videos.isArray() && videos.size() > 0) {
                        String filename = videos.get(0).path("filename").asText(null);
                        if (hasText(filename)) {
                            log.info("ComfyUI 视频生成完成, filename={}", filename);
                            return filename;
                        }
                    }
                    JsonNode images = entry.getValue().path("images");
                    if (images.isArray() && images.size() > 0) {
                        String filename = images.get(0).path("filename").asText(null);
                        if (hasText(filename)) {
                            log.info("ComfyUI 输出完成 (images), filename={}", filename);
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
                    throw new RuntimeException("ComfyUI 视频任务执行失败: " + msg);
                }
            }
        }
        throw new RuntimeException("ComfyUI 视频生成轮询超时 (prompt_id=" + promptId + ")");
    }

    private int[] parseResolution(String resolution) {
        if (!hasText(resolution)) {
            return new int[]{480, 854};
        }
        return switch (resolution.toUpperCase()) {
            case "1080P" -> new int[]{1080, 1920};
            case "720P" -> new int[]{720, 1280};
            case "480P" -> new int[]{480, 854};
            default -> {
                String[] parts = resolution.toLowerCase().split("[xX×]");
                if (parts.length == 2) {
                    try {
                        yield new int[]{
                                Integer.parseInt(parts[0].trim()),
                                Integer.parseInt(parts[1].trim())
                        };
                    } catch (NumberFormatException ignored) {
                    }
                }
                yield new int[]{480, 854};
            }
        };
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
                        log.info("ComfyUI 自动选择视频模型: {}", firstModel);
                        return firstModel;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("无法查询 ComfyUI 可用模型，使用默认值: {}", e.getMessage());
        }
        return "wan2.1_i2v_480p_14B_fp8.safetensors";
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
