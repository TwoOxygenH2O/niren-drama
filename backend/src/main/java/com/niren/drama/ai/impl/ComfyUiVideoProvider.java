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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class ComfyUiVideoProvider implements VideoAiProvider {

    private static final int DEFAULT_MAX_POLL_ATTEMPTS = 180;
    private static final long DEFAULT_POLL_INTERVAL_MS = 3000L;

    private final String apiBaseUrl;
    private final String apiKey;
    private final String model;
    private final String uploadPath;
    private final String publicBaseUrl;
    private final String extra;
    private final int maxPollAttempts;
    private final long pollIntervalMs;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ComfyUiVideoProvider(String baseUrl, String apiKey, String model, String extra,
                                String uploadPath, String publicBaseUrl) {
        this(baseUrl, apiKey, model, extra, uploadPath, publicBaseUrl,
                DEFAULT_MAX_POLL_ATTEMPTS, DEFAULT_POLL_INTERVAL_MS);
    }

    ComfyUiVideoProvider(String baseUrl, String apiKey, String model, String extra,
                         String uploadPath, String publicBaseUrl,
                         int maxPollAttempts, long pollIntervalMs) {
        this.apiBaseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = hasText(model) ? model : "";
        this.extra = extra;
        this.uploadPath = uploadPath;
        this.publicBaseUrl = publicBaseUrl;
        this.maxPollAttempts = maxPollAttempts;
        this.pollIntervalMs = pollIntervalMs;
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

    private static final String DEFAULT_NEGATIVE_PROMPT =
            "低质量, 模糊, 扭曲, 变形, 丑陋, 多余手指, 畸形手, 文字, 水印, "
          + "low quality, blurry, distorted, deformed, ugly, extra fingers, bad anatomy, text, watermark, "
          + "jpeg artifacts, worst quality, bad proportions, duplicate, mutation";

    private ObjectNode buildTextToVideoWorkflow(String prompt, int width, int height, int frames) {
        String workflowFile = null;
        if (hasText(extra)) {
            try {
                JsonNode extraJson = objectMapper.readTree(extra);
                JsonNode workflowNode = extraJson.path("workflow");
                if (workflowNode.isObject()) {
                    ObjectNode wf = (ObjectNode) workflowNode.deepCopy();
                    injectPromptIntoWorkflow(wf, prompt);
                    injectVideoParams(wf, width, height, frames);
                    return wf;
                }
                workflowFile = extraJson.path("workflowFile").asText(null);
            } catch (Exception ignored) {
            }
        }

        // 默认使用 classpath 模板，避免从 ComfyUI 加载不兼容的用户工作流
        String defaultT2vTemplate = "video_ltx2_t2v_distilled.json";
        ObjectNode template = hasText(workflowFile)
                ? ComfyUiWorkflowLoader.loadWorkflow(apiBaseUrl, httpClient, workflowFile)
                : ComfyUiWorkflowLoader.loadWorkflow(apiBaseUrl, httpClient, defaultT2vTemplate);
        if (template != null) {
            ComfyUiWorkflowLoader.injectPrompt(template, prompt);
            injectNegativePromptToWorkflow(template);
            injectVideoParams(template, width, height, frames);
            return template;
        }

        return buildDefaultVideoWorkflow(prompt, null, width, height, frames);
    }

    private ObjectNode buildImageToVideoWorkflow(String imageUrl, String prompt,
                                                  int width, int height, int frames) throws Exception {
        String workflowFile = null;
        if (hasText(extra)) {
            try {
                JsonNode extraJson = objectMapper.readTree(extra);
                JsonNode workflowNode = extraJson.path("workflow");
                if (workflowNode.isObject()) {
                    ObjectNode wf = (ObjectNode) workflowNode.deepCopy();
                    injectPromptIntoWorkflow(wf, prompt);
                    injectImageIntoWorkflow(wf, uploadImageIfRemote(imageUrl));
                    injectNegativePromptToWorkflow(wf);
                    injectVideoParams(wf, width, height, frames);
                    return wf;
                }
                workflowFile = extraJson.path("workflowFile").asText(null);
            } catch (Exception ignored) {
            }
        }

        // 默认使用 classpath 模板，避免从 ComfyUI 加载不兼容的用户工作流
        String defaultI2vTemplate = "video_ltx2_i2v_distilled.json";
        ObjectNode template = hasText(workflowFile)
                ? ComfyUiWorkflowLoader.loadWorkflow(apiBaseUrl, httpClient, workflowFile)
                : ComfyUiWorkflowLoader.loadWorkflow(apiBaseUrl, httpClient, defaultI2vTemplate);
        if (template != null) {
            String comfyImage = uploadImageIfRemote(imageUrl);
            ComfyUiWorkflowLoader.injectPrompt(template, prompt);
            ComfyUiWorkflowLoader.injectImage(template, comfyImage);
            injectNegativePromptToWorkflow(template);
            injectVideoParams(template, width, height, frames);
            return template;
        }

        return buildDefaultVideoWorkflow(prompt, uploadImageIfRemote(imageUrl), width, height, frames);
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

    /**
     * 向工作流注入负向提示词。查找第二个 CLIPTextEncode 节点（或专用 negative prompt 节点），
     * 如果不存在则自动创建。
     */
    private void injectNegativePromptToWorkflow(ObjectNode workflow) {
        // 先尝试找到已有的 negative prompt 节点
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (!node.isObject()) continue;
            String classType = node.path("class_type").asText("");
            JsonNode inputs = node.path("inputs");
            // 查找包含 "negative" 关键字的文本输入
            for (var inIt = inputs.fields(); inIt.hasNext(); ) {
                var inEntry = inIt.next();
                if (inEntry.getKey().toLowerCase().contains("negative")
                        && inEntry.getValue().isTextual()) {
                    ((ObjectNode) inputs).put(inEntry.getKey(), DEFAULT_NEGATIVE_PROMPT);
                    return;
                }
            }
        }

        // 没有专用 negative 节点 → 查找 KSampler/sampler 节点的 negative 输入引用
        // 找到 sampler，看它的 negative 输入引用了哪个节点
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (!node.isObject()) continue;
            String classType = node.path("class_type").asText("");
            if (!isSamplerNode(classType)) continue;

            JsonNode negInput = node.path("inputs").path("negative");
            if (negInput.isArray() && negInput.size() >= 1 && negInput.get(0).isTextual()) {
                String negNodeId = negInput.get(0).asText();
                JsonNode negNode = workflow.path(negNodeId);
                if (negNode.isObject() && negNode.path("inputs").path("text").isTextual()) {
                    ((ObjectNode) negNode.path("inputs")).put("text", DEFAULT_NEGATIVE_PROMPT);
                    return;
                }
            }
        }

        // 找不到 → 尝试创建一个简单的 negative prompt 节点
        // 找到 CLIP 引用来创建
        String clipRef = findClipReferenceForNegative(workflow);
        if (clipRef == null) return;

        String negNodeId = String.valueOf(Integer.parseInt(findMaxNodeId(workflow)) + 1);
        ObjectNode negClip = workflow.putObject(negNodeId);
        negClip.put("class_type", "CLIPTextEncode");
        ObjectNode negInputs = negClip.putObject("inputs");
        negInputs.put("text", DEFAULT_NEGATIVE_PROMPT);
        ArrayNode clipArr = negInputs.putArray("clip");
        clipArr.add(clipRef);
        clipArr.add(1);

        // 连接到 sampler 的 negative 输入
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (!node.isObject()) continue;
            if (isSamplerNode(node.path("class_type").asText(""))) {
                JsonNode negIn = node.path("inputs").path("negative");
                if (negIn.isArray() && negIn.size() >= 1) {
                    ArrayNode newNeg = ((ObjectNode) node.path("inputs")).putArray("negative");
                    newNeg.add(negNodeId);
                    newNeg.add(0);
                    log.info("自动创建 negative prompt 节点 {} 并连接到 sampler", negNodeId);
                    return;
                }
            }
        }
    }

    /**
     * 向工作流注入视频参数：帧数、分辨率、帧率。
     * 使模板工作流中的 duration 参数真正生效。
     */
    private void injectVideoParams(ObjectNode workflow, int width, int height, int frames) {
        int fps = 8; // 统一使用 8fps
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (!node.isObject()) continue;
            String classType = node.path("class_type").asText("");
            ObjectNode inputs = (ObjectNode) node.path("inputs");

            switch (classType) {
                // 视频合成节点 → 统一帧率
                case "VHS_VideoCombine":
                case "SaveVideo":
                case "CreateVideo":
                    inputs.put("frame_rate", fps);
                    break;
                // 潜在空间设置
                case "EmptyLatentImage":
                    inputs.put("width", width);
                    inputs.put("height", height);
                    inputs.put("batch_size", frames);
                    break;
                case "EmptyLTXVLatentVideo":
                case "EmptyHunyuanLatentVideo":
                case "EmptyHunyuanVideo15Latent":
                    inputs.put("width", width);
                    inputs.put("height", height);
                    if (inputs.has("length")) {
                        inputs.put("length", frames);
                    }
                    if (inputs.has("num_frames")) {
                        inputs.put("num_frames", frames);
                    }
                    if (inputs.has("batch_size")) {
                        inputs.put("batch_size", frames);
                    }
                    break;
                // LTX conditioning
                case "LTXVConditioning":
                    inputs.put("width", width);
                    inputs.put("height", height);
                    inputs.put("num_frames", frames);
                    break;
                // WAN / Hunyuan sampler
                case "WanVideoSampler":
                case "WanVideoSamplerv2":
                case "LTXVScheduler":
                    if (inputs.has("num_frames")) {
                        inputs.put("num_frames", frames);
                    }
                    break;
                // Hunyuan latent
                case "HunyuanImageToVideo":
                case "HunyuanVideo15ImageToVideo":
                    inputs.put("width", width);
                    inputs.put("height", height);
                    if (inputs.has("length")) {
                        inputs.put("length", frames);
                    }
                    if (inputs.has("num_frames")) {
                        inputs.put("num_frames", frames);
                    }
                    break;
                // LTX image to video
                case "LTXVImgToVideo":
                case "LTXVImgToVideoAdvanced":
                    inputs.put("width", width);
                    inputs.put("height", height);
                    if (inputs.has("length")) {
                        inputs.put("length", frames);
                    }
                    if (inputs.has("num_frames")) {
                        inputs.put("num_frames", frames);
                    }
                    break;
            }
        }
        log.debug("注入视频参数: width={}, height={}, frames={}, fps={}", width, height, frames, fps);
    }

    private boolean isSamplerNode(String classType) {
        return "KSampler".equals(classType)
                || "KSamplerAdvanced".equals(classType)
                || "WanVideoSampler".equals(classType)
                || "WanVideoSamplerv2".equals(classType)
                || "LTXVBaseSampler".equals(classType)
                || "LTXVScheduler".equals(classType);
    }

    private String findClipReferenceForNegative(ObjectNode workflow) {
        // 从 positive prompt 节点获取 CLIP 引用
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (!node.isObject()) continue;
            String classType = node.path("class_type").asText("");
            if ("CLIPTextEncode".equals(classType)) {
                JsonNode clip = node.path("inputs").path("clip");
                if (clip.isArray() && clip.size() >= 1 && clip.get(0).isTextual()) {
                    return clip.get(0).asText();
                }
            }
        }
        return null;
    }

    private String findMaxNodeId(ObjectNode workflow) {
        int max = 0;
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            try {
                int id = Integer.parseInt(entry.getKey());
                if (id > max) max = id;
            } catch (NumberFormatException ignored) {
            }
        }
        return String.valueOf(max);
    }

    private String uploadImageIfRemote(String imageUrl) throws Exception {
        if (!isHttpUrl(imageUrl)) {
            return imageUrl;
        }

        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .GET()
                .timeout(Duration.ofSeconds(180))
                .build();
        HttpResponse<byte[]> downloadResponse = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());
        byte[] imageBytes = downloadResponse.body();
        if (downloadResponse.statusCode() >= 400 || imageBytes == null || imageBytes.length == 0) {
            throw new RuntimeException("下载图生视频输入图片失败: HTTP " + downloadResponse.statusCode());
        }

        String boundary = "----niren-comfyui-" + UUID.randomUUID().toString().replace("-", "");
        String filename = resolveUploadFilename(imageUrl, downloadResponse.headers().firstValue("Content-Type").orElse(null));
        byte[] body = buildMultipartBody(boundary, filename, imageBytes);
        HttpRequest uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/upload/image"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(180))
                .build();
        HttpResponse<String> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
        if (uploadResponse.statusCode() >= 400) {
            throw new RuntimeException("上传图片到 ComfyUI 失败: HTTP " + uploadResponse.statusCode() + " - " + uploadResponse.body());
        }

        JsonNode root = objectMapper.readTree(uploadResponse.body());
        String name = root.path("name").asText(null);
        String subfolder = root.path("subfolder").asText("");
        if (!hasText(name)) {
            throw new RuntimeException("ComfyUI 上传图片未返回文件名: " + uploadResponse.body());
        }
        return hasText(subfolder) ? subfolder + "/" + name : name;
    }

    private byte[] buildMultipartBody(String boundary, String filename, byte[] imageBytes) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headerBytes.length + imageBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(imageBytes, 0, body, headerBytes.length, imageBytes.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + imageBytes.length, footerBytes.length);
        return body;
    }

    private String resolveUploadFilename(String imageUrl, String contentType) {
        String extension = "png";
        if (hasText(contentType)) {
            String normalized = contentType.toLowerCase();
            if (normalized.contains("jpeg") || normalized.contains("jpg")) {
                extension = "jpg";
            } else if (normalized.contains("webp")) {
                extension = "webp";
            }
        }
        try {
            String path = URI.create(imageUrl).getPath();
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            if (hasText(name) && name.contains(".")) {
                return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            }
        } catch (Exception ignored) {
        }
        return UUID.randomUUID().toString().replace("-", "") + "." + extension;
    }

    private boolean isHttpUrl(String value) {
        return hasText(value) && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private String pollForResult(String promptId) throws Exception {
        String historyUrl = apiBaseUrl + "/history/" + promptId;
        int attempt = 0;
        while (true) {
            Thread.sleep(pollIntervalMs);
            attempt++;

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
                // 每 maxPollAttempts 次检查一次队列状态，确认任务还在
                if (attempt % maxPollAttempts == 0) {
                    String queueStatus = checkQueueStatus(promptId);
                    if ("completed".equals(queueStatus)) {
                        throw new RuntimeException("ComfyUI 任务可能已完成但 history 未返回，请检查 ComfyUI 输出目录 (prompt_id=" + promptId + ")");
                    }
                    if ("not_found".equals(queueStatus)) {
                        throw new RuntimeException("ComfyUI 视频任务已不在队列中，可能已被清理 (prompt_id=" + promptId + ")");
                    }
                    log.info("ComfyUI 视频任务仍在执行中 (prompt_id={}, attempt={}, queueStatus={})",
                            promptId, attempt, queueStatus);
                }
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

            // 周期性打印进度日志
            if (attempt % 20 == 0) {
                log.info("ComfyUI 视频生成轮询中 (prompt_id={}, attempt={})", promptId, attempt);
            }
        }
    }

    /**
     * 检查 ComfyUI 队列中 prompt 的状态。
     * @return "running" | "pending" | "completed" | "not_found"
     */
    private String checkQueueStatus(String promptId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/queue"))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return "unknown";
            }
            JsonNode queue = objectMapper.readTree(response.body());
            if (containsPromptId(queue.path("queue_running"), promptId)) {
                return "running";
            }
            if (containsPromptId(queue.path("queue_pending"), promptId)) {
                return "pending";
            }
            return "not_found";
        } catch (Exception e) {
            log.debug("查询 ComfyUI 队列状态失败: {}", e.getMessage());
            return "unknown";
        }
    }

    private boolean containsPromptId(JsonNode queueItems, String promptId) {
        if (!queueItems.isArray()) {
            return false;
        }
        for (JsonNode item : queueItems) {
            if (item.isArray()) {
                for (JsonNode value : item) {
                    if (promptId.equals(value.asText(null))) {
                        return true;
                    }
                }
            }
        }
        return false;
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
