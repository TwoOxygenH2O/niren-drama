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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
public class ComfyUiVideoProvider implements VideoAiProvider {

    private static final int DEFAULT_MAX_POLL_ATTEMPTS = 14_400;
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
            int frames = videoFrames(duration);
            ObjectNode workflow = buildTextToVideoWorkflow(prompt, wh[0], wh[1], frames, quality);
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
            OutputInfo outputInfo = pollForResult(promptId);
            videoUrl = buildOutputViewUrl(outputInfo);
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
            int frames = videoFrames(duration);
            ObjectNode workflow = buildImageToVideoWorkflow(imageUrl, List.of(), prompt, wh[0], wh[1], frames, quality);
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
            OutputInfo outputInfo = pollForResult(promptId);
            videoUrl = buildOutputViewUrl(outputInfo);
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
    public String generateVideoFromImage(String imageUrl, List<String> referenceImageUrls, String prompt, int duration,
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
            int frames = videoFrames(duration);
            String enhancedPrompt = buildShortDramaVideoPrompt(prompt, referenceImageUrls);
            ObjectNode workflow = buildImageToVideoWorkflow(imageUrl, referenceImageUrls, enhancedPrompt, wh[0], wh[1], frames, quality);
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

            log.info("ComfyUI 视频生成任务已提交 (image2video), prompt_id={}, refCount={}",
                    promptId, normalizeReferenceImageUrls(imageUrl, referenceImageUrls).size());
            OutputInfo outputInfo = pollForResult(promptId);
            videoUrl = buildOutputViewUrl(outputInfo);
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

    /**
     * 计算符合 LTX-2 约束的帧数（基于 24fps 输出帧率）。
     * LTX-2 要求 {@code (num_frames - 1) % 8 == 0}。
     */
    private static int ltxFrames(int durationSeconds) {
        int base = Math.max(1, durationSeconds * 24);
        return ((base - 1 + 7) / 8) * 8 + 1;
    }

    private static int wanFrames(int durationSeconds) {
        int base = Math.max(17, durationSeconds * 16);
        return ((base - 1 + 3) / 4) * 4 + 1;
    }

    private int videoFrames(int durationSeconds) {
        int normalizedDuration = Math.min(Math.max(durationSeconds, 3), 10);
        if (isWanModel()) {
            return wanFrames(normalizedDuration);
        }
        return ltxFrames(normalizedDuration);
    }

    private static final String DEFAULT_NEGATIVE_PROMPT =
            "低质量, 模糊, 扭曲, 变形, 丑陋, 多余手指, 畸形手, 文字, 水印, "
          + "low quality, blurry, distorted, deformed, ugly, extra fingers, bad anatomy, text, watermark, "
          + "jpeg artifacts, worst quality, bad proportions, duplicate, mutation, "
          + "anime, cartoon, illustration, CGI, 3D render, plastic skin, wax figure, "
          + "sketch, line art, pencil drawing, hand drawn, monochrome, black and white, storyboard, comic panel, manga, cel shading, "
          + "slideshow, still frame, frozen frame, static image, no motion, posterized, over-sharpened, "
          + "face morphing, identity drift, different person, new person, extra person, unwanted person, changing clothes, scene jump, camera cut, "
          + "split screen, duplicated character, flicker, jitter, warped hands, melted face, subtitles, logo, "
          + "动漫, 二次元, 插画, 卡通, 游戏CG, 塑料感, 蜡像, 3D渲染, 线稿, 素描, 铅笔画, 手绘, 黑白, 漫画分镜, 静帧, 幻灯片, 无动作, "
          + "换脸, 换衣服, 跳场, 闪烁, 字幕, 平台水印";

    /** 构建 LTX-2 T2V 最小工作流（使用 Gemma CLIP loader） */
    private ObjectNode buildLtx2T2vWorkflow(String prompt, int width, int height, int frames) {
        ObjectNode wf = objectMapper.createObjectNode();

        // Node 1: CheckpointLoaderSimple (diffusion model + VAE only)
        ObjectNode loader = wf.putObject("1");
        loader.put("class_type", "CheckpointLoaderSimple");
        ObjectNode loaderInputs = loader.putObject("inputs");
        loaderInputs.put("ckpt_name", resolveCheckpointModel());

        // Node 2: LTXAVTextEncoderLoader (works with single-file Gemma model)
        ObjectNode gemma = wf.putObject("2");
        gemma.put("class_type", "LTXAVTextEncoderLoader");
        ObjectNode gemmaInputs = gemma.putObject("inputs");
        gemmaInputs.put("text_encoder", "gemma_3_12B_it_fp4_mixed.safetensors");
        gemmaInputs.put("ckpt_name", "ltx-2-19b-distilled.safetensors");
        gemmaInputs.put("device", "default");

        // Node 3: CLIPTextEncode (positive prompt, uses Gemma CLIP)
        ObjectNode posClip = wf.putObject("3");
        posClip.put("class_type", "CLIPTextEncode");
        ObjectNode posInputs = posClip.putObject("inputs");
        posInputs.put("text", prompt);
        ArrayNode clipRef = posInputs.putArray("clip");
        clipRef.add("2"); clipRef.add(0);

        // Node 4: LTXVConditioning
        ObjectNode cond = wf.putObject("4");
        cond.put("class_type", "LTXVConditioning");
        ObjectNode condInputs = cond.putObject("inputs");
        ArrayNode condPos = condInputs.putArray("positive");
        condPos.add("3"); condPos.add(0);
        ArrayNode condNeg = condInputs.putArray("negative");
        condNeg.add("3"); condNeg.add(0);
        condInputs.put("frame_rate", 24.0);
        condInputs.put("width", width);
        condInputs.put("height", height);
        condInputs.put("num_frames", frames);

        // Node 5: EmptyLTXVLatentVideo
        ObjectNode vidLatent = wf.putObject("5");
        vidLatent.put("class_type", "EmptyLTXVLatentVideo");
        ObjectNode vidLatentInputs = vidLatent.putObject("inputs");
        vidLatentInputs.put("width", width);
        vidLatentInputs.put("height", height);
        vidLatentInputs.put("length", frames);
        vidLatentInputs.put("batch_size", 1);

        // Node 6: RandomNoise
        ObjectNode noise = wf.putObject("6");
        noise.put("class_type", "RandomNoise");
        ObjectNode noiseInputs = noise.putObject("inputs");
        noiseInputs.put("noise_seed", (int)(Math.random() * Integer.MAX_VALUE));

        // Node 7: CFGGuider
        ObjectNode guider = wf.putObject("7");
        guider.put("class_type", "CFGGuider");
        ObjectNode guiderInputs = guider.putObject("inputs");
        ArrayNode gModel = guiderInputs.putArray("model");
        gModel.add("1"); gModel.add(0);
        ArrayNode gPos = guiderInputs.putArray("positive");
        gPos.add("4"); gPos.add(0);
        ArrayNode gNeg = guiderInputs.putArray("negative");
        gNeg.add("4"); gNeg.add(1);
        guiderInputs.put("cfg", 2.0);

        // Node 8: KSamplerSelect
        ObjectNode kss = wf.putObject("8");
        kss.put("class_type", "KSamplerSelect");
        ObjectNode kssInputs = kss.putObject("inputs");
        kssInputs.put("sampler_name", "euler");

        // Node 9: BasicScheduler
        ObjectNode sched = wf.putObject("9");
        sched.put("class_type", "BasicScheduler");
        ObjectNode schedInputs = sched.putObject("inputs");
        schedInputs.put("scheduler", "normal");
        schedInputs.put("steps", 8);
        schedInputs.put("denoise", 1.0);
        ArrayNode schedModel = schedInputs.putArray("model");
        schedModel.add("1"); schedModel.add(0);

        // Node 10: SamplerCustomAdvanced
        ObjectNode sampler = wf.putObject("10");
        sampler.put("class_type", "SamplerCustomAdvanced");
        ObjectNode samplerInputs = sampler.putObject("inputs");
        ArrayNode sNoise = samplerInputs.putArray("noise");
        sNoise.add("6"); sNoise.add(0);
        ArrayNode sGuider = samplerInputs.putArray("guider");
        sGuider.add("7"); sGuider.add(0);
        ArrayNode sSampler = samplerInputs.putArray("sampler");
        sSampler.add("8"); sSampler.add(0);
        ArrayNode sSigmas = samplerInputs.putArray("sigmas");
        sSigmas.add("9"); sSigmas.add(0);
        ArrayNode sLatent = samplerInputs.putArray("latent_image");
        sLatent.add("5"); sLatent.add(0);

        // Node 11: VAEDecode
        ObjectNode vaeDecode = wf.putObject("11");
        vaeDecode.put("class_type", "VAEDecode");
        ObjectNode vaeInputs = vaeDecode.putObject("inputs");
        ArrayNode vaeSamples = vaeInputs.putArray("samples");
        vaeSamples.add("10"); vaeSamples.add(0);
        ArrayNode vaeRef = vaeInputs.putArray("vae");
        vaeRef.add("1"); vaeRef.add(2);

        // Node 12: VHS_VideoCombine
        ObjectNode vhs = wf.putObject("12");
        vhs.put("class_type", "VHS_VideoCombine");
        ObjectNode vhsInputs = vhs.putObject("inputs");
        vhsInputs.put("frame_rate", 24);
        vhsInputs.put("loop_count", 0);
        vhsInputs.put("filename_prefix", "niren_video");
        vhsInputs.put("format", "video/h264-mp4");
        vhsInputs.put("save_output", false);
        vhsInputs.put("pingpong", false);
        ArrayNode vhsImages = vhsInputs.putArray("images");
        vhsImages.add("11"); vhsImages.add(0);

        return wf;
    }

    private ObjectNode buildTextToVideoWorkflow(String prompt, int width, int height, int frames, String quality) {
        if (isWanModel()) {
            return buildWan22T2vWorkflow(prompt, width, height, frames, quality);
        }
        return buildLtx2T2vWorkflow(prompt, width, height, frames);
    }

    /** 构建 LTX-2 I2V 工作流：主关键帧锁首帧，独立正/负提示词，适合 3-10 秒短剧镜头。 */
    private ObjectNode buildLtx2I2vWorkflow(String imageUrl, String prompt, int width, int height, int frames) {
        ObjectNode wf = objectMapper.createObjectNode();

        // Node 1: CheckpointLoaderSimple
        ObjectNode loader = wf.putObject("1");
        loader.put("class_type", "CheckpointLoaderSimple");
        ObjectNode loaderInputs = loader.putObject("inputs");
        loaderInputs.put("ckpt_name", resolveCheckpointModel());

        // Node 2: LTXAVTextEncoderLoader
        ObjectNode gemma = wf.putObject("2");
        gemma.put("class_type", "LTXAVTextEncoderLoader");
        ObjectNode gemmaInputs = gemma.putObject("inputs");
        gemmaInputs.put("text_encoder", "gemma_3_12B_it_fp4_mixed.safetensors");
        gemmaInputs.put("ckpt_name", "ltx-2-19b-distilled.safetensors");
        gemmaInputs.put("device", "default");

        // Node 3: CLIPTextEncode (positive prompt)
        ObjectNode posClip = wf.putObject("3");
        posClip.put("class_type", "CLIPTextEncode");
        ObjectNode posInputs = posClip.putObject("inputs");
        posInputs.put("text", prompt);
        ArrayNode clipRef = posInputs.putArray("clip");
        clipRef.add("2"); clipRef.add(0);

        // Node 4: CLIPTextEncode (negative prompt)
        ObjectNode negClip = wf.putObject("4");
        negClip.put("class_type", "CLIPTextEncode");
        ObjectNode negInputs = negClip.putObject("inputs");
        negInputs.put("text", DEFAULT_NEGATIVE_PROMPT);
        ArrayNode negClipRef = negInputs.putArray("clip");
        negClipRef.add("2"); negClipRef.add(0);

        // Node 5: LTXVConditioning
        ObjectNode cond = wf.putObject("5");
        cond.put("class_type", "LTXVConditioning");
        ObjectNode condInputs = cond.putObject("inputs");
        ArrayNode condPos = condInputs.putArray("positive");
        condPos.add("3"); condPos.add(0);
        ArrayNode condNeg = condInputs.putArray("negative");
        condNeg.add("4"); condNeg.add(0);
        condInputs.put("frame_rate", 24.0);

        // Node 6: LoadImage (primary shot keyframe for I2V)
        ObjectNode loadImg = wf.putObject("6");
        loadImg.put("class_type", "LoadImage");
        ObjectNode loadImgInputs = loadImg.putObject("inputs");
        loadImgInputs.put("image", imageUrl);

        // Node 7: EmptyLTXVLatentVideo (target video latent space)
        ObjectNode vidLatent = wf.putObject("7");
        vidLatent.put("class_type", "EmptyLTXVLatentVideo");
        ObjectNode vidLatentInputs = vidLatent.putObject("inputs");
        vidLatentInputs.put("width", width);
        vidLatentInputs.put("height", height);
        vidLatentInputs.put("length", frames);
        vidLatentInputs.put("batch_size", 1);

        // Node 8: LTXVImgToVideoConditionOnly (inject reference frame into video latent)
        ObjectNode i2vCond = wf.putObject("8");
        i2vCond.put("class_type", "LTXVImgToVideoConditionOnly");
        ObjectNode i2vCondInputs = i2vCond.putObject("inputs");
        ArrayNode i2vImg = i2vCondInputs.putArray("image");
        i2vImg.add("6"); i2vImg.add(0);
        ArrayNode i2vVae = i2vCondInputs.putArray("vae");
        i2vVae.add("1"); i2vVae.add(2);
        ArrayNode i2vLatent = i2vCondInputs.putArray("latent");
        i2vLatent.add("7"); i2vLatent.add(0);
        i2vCondInputs.put("strength", 0.95);
        i2vCondInputs.put("bypass", false);

        // Node 9: RandomNoise
        ObjectNode noise = wf.putObject("9");
        noise.put("class_type", "RandomNoise");
        ObjectNode noiseInputs = noise.putObject("inputs");
        noiseInputs.put("noise_seed", (int)(Math.random() * Integer.MAX_VALUE));

        // Node 10: CFGGuider
        ObjectNode guider = wf.putObject("10");
        guider.put("class_type", "CFGGuider");
        ObjectNode guiderInputs = guider.putObject("inputs");
        ArrayNode gModel = guiderInputs.putArray("model");
        gModel.add("1"); gModel.add(0);
        ArrayNode gPos = guiderInputs.putArray("positive");
        gPos.add("5"); gPos.add(0);
        ArrayNode gNeg = guiderInputs.putArray("negative");
        gNeg.add("5"); gNeg.add(1);
        guiderInputs.put("cfg", 2.0);

        // Node 11: KSamplerSelect
        ObjectNode kss = wf.putObject("11");
        kss.put("class_type", "KSamplerSelect");
        ObjectNode kssInputs = kss.putObject("inputs");
        kssInputs.put("sampler_name", "euler");

        // Node 12: BasicScheduler
        ObjectNode sched = wf.putObject("12");
        sched.put("class_type", "BasicScheduler");
        ObjectNode schedInputs = sched.putObject("inputs");
        schedInputs.put("scheduler", "normal");
        schedInputs.put("steps", 10);
        schedInputs.put("denoise", 1.0);
        ArrayNode schedModel = schedInputs.putArray("model");
        schedModel.add("1"); schedModel.add(0);

        // Node 13: SamplerCustomAdvanced (uses I2V-conditioned latent instead of empty latent)
        ObjectNode sampler = wf.putObject("13");
        sampler.put("class_type", "SamplerCustomAdvanced");
        ObjectNode samplerInputs = sampler.putObject("inputs");
        ArrayNode sNoise = samplerInputs.putArray("noise");
        sNoise.add("9"); sNoise.add(0);
        ArrayNode sGuider = samplerInputs.putArray("guider");
        sGuider.add("10"); sGuider.add(0);
        ArrayNode sSampler = samplerInputs.putArray("sampler");
        sSampler.add("11"); sSampler.add(0);
        ArrayNode sSigmas = samplerInputs.putArray("sigmas");
        sSigmas.add("12"); sSigmas.add(0);
        ArrayNode sLatent = samplerInputs.putArray("latent_image");
        sLatent.add("8"); sLatent.add(0);  // ← 使用 I2V 条件化后的 latent

        // Node 14: VAEDecode
        ObjectNode vaeDecode = wf.putObject("14");
        vaeDecode.put("class_type", "VAEDecode");
        ObjectNode vaeInputs = vaeDecode.putObject("inputs");
        ArrayNode vaeSamples = vaeInputs.putArray("samples");
        vaeSamples.add("13"); vaeSamples.add(0);
        ArrayNode vaeRef = vaeInputs.putArray("vae");
        vaeRef.add("1"); vaeRef.add(2);

        // Node 15: VHS_VideoCombine
        ObjectNode vhs = wf.putObject("15");
        vhs.put("class_type", "VHS_VideoCombine");
        ObjectNode vhsInputs = vhs.putObject("inputs");
        vhsInputs.put("frame_rate", 24);
        vhsInputs.put("loop_count", 0);
        vhsInputs.put("filename_prefix", "niren_i2v");
        vhsInputs.put("format", "video/h264-mp4");
        vhsInputs.put("save_output", false);
        vhsInputs.put("pingpong", false);
        ArrayNode vhsImages = vhsInputs.putArray("images");
        vhsImages.add("14"); vhsImages.add(0);

        return wf;
    }

    private boolean isWanModel() {
        return hasText(model) && model.toLowerCase().contains("wan");
    }

    /** 构建 Wan2.2 T2V 工作流，优先从 classpath 模板加载 */
    private ObjectNode buildWan22T2vWorkflow(String prompt, int width, int height, int frames, String quality) {
        String templateName = "video_wan2_2_14B_t2v.json";
        ObjectNode template = ComfyUiWorkflowLoader.loadWorkflow(apiBaseUrl, httpClient, templateName);
        if (template != null) {
            ComfyUiWorkflowLoader.injectPrompt(template, prompt);
            injectNegativePromptToWorkflow(template);
            injectVideoParams(template, width, height, frames, resolveWorkflowTuning(prompt, quality, frames), 0);
            return template;
        }
        return buildWan22T2vWorkflowInline(prompt, width, height, frames);
    }

    /** Wan2.2 T2V 内联构建（模板加载失败时的回退） */
    private ObjectNode buildWan22T2vWorkflowInline(String prompt, int width, int height, int frames) {
        ObjectNode wf = objectMapper.createObjectNode();

        // Node 1: WanVideoModelLoader
        ObjectNode modelLoader = wf.putObject("1");
        modelLoader.put("class_type", "WanVideoModelLoader");
        ObjectNode mlInputs = modelLoader.putObject("inputs");
        mlInputs.put("model", resolveCheckpointModel());
        mlInputs.put("precision", "fp8_scaled");

        // Node 2: WanVideoVAELoader
        ObjectNode vaeLoader = wf.putObject("2");
        vaeLoader.put("class_type", "WanVideoVAELoader");
        ObjectNode vlInputs = vaeLoader.putObject("inputs");
        vlInputs.put("vae_name", "wan_2.1_vae.safetensors");

        // Node 3: WanVideoTextEncode
        ObjectNode textEnc = wf.putObject("3");
        textEnc.put("class_type", "WanVideoTextEncode");
        ObjectNode teInputs = textEnc.putObject("inputs");
        teInputs.put("positive_prompt", prompt);
        teInputs.put("negative_prompt", DEFAULT_NEGATIVE_PROMPT);
        ArrayNode teClip = teInputs.putArray("clip");
        teClip.add("1"); teClip.add(1);

        // Node 4: EmptyHunyuanLatentVideo
        ObjectNode latent = wf.putObject("4");
        latent.put("class_type", "EmptyHunyuanLatentVideo");
        ObjectNode latentInputs = latent.putObject("inputs");
        latentInputs.put("width", width);
        latentInputs.put("height", height);
        latentInputs.put("length", frames);

        // Node 5: WanVideoSampler
        ObjectNode sampler = wf.putObject("5");
        sampler.put("class_type", "WanVideoSampler");
        ObjectNode sInputs = sampler.putObject("inputs");
        sInputs.put("seed", (int)(Math.random() * Integer.MAX_VALUE));
        sInputs.put("steps", 20);
        sInputs.put("cfg", 6.0);
        ArrayNode sModel = sInputs.putArray("model");
        sModel.add("1"); sModel.add(0);
        ArrayNode sPos = sInputs.putArray("positive");
        sPos.add("3"); sPos.add(0);
        ArrayNode sNeg = sInputs.putArray("negative");
        sNeg.add("3"); sNeg.add(1);
        ArrayNode sLatent = sInputs.putArray("latent_image");
        sLatent.add("4"); sLatent.add(0);

        // Node 6: WanVideoDecode
        ObjectNode decode = wf.putObject("6");
        decode.put("class_type", "WanVideoDecode");
        ObjectNode dInputs = decode.putObject("inputs");
        ArrayNode dSamples = dInputs.putArray("samples");
        dSamples.add("5"); dSamples.add(0);
        ArrayNode dVae = dInputs.putArray("vae");
        dVae.add("2"); dVae.add(0);

        // Node 7: VHS_VideoCombine
        ObjectNode vhs = wf.putObject("7");
        vhs.put("class_type", "VHS_VideoCombine");
        ObjectNode vhsInputs = vhs.putObject("inputs");
        vhsInputs.put("frame_rate", 8);
        vhsInputs.put("loop_count", 0);
        vhsInputs.put("filename_prefix", "niren_video");
        vhsInputs.put("format", "video/h264-mp4");
        vhsInputs.put("save_output", false);
        vhsInputs.put("pingpong", false);
        ArrayNode vhsImages = vhsInputs.putArray("images");
        vhsImages.add("6"); vhsImages.add(0);

        return wf;
    }

    private ObjectNode buildImageToVideoWorkflow(String imageUrl, String prompt,
                                                  int width, int height, int frames) throws Exception {
        return buildImageToVideoWorkflow(imageUrl, List.of(), prompt, width, height, frames, null);
    }

    private ObjectNode buildImageToVideoWorkflow(String imageUrl, List<String> referenceImageUrls, String prompt,
                                                  int width, int height, int frames, String quality) throws Exception {
        String workflowFile = null;
        String primaryImage = uploadImageIfRemote(imageUrl);
        List<String> uploadedReferences = uploadReferenceImages(normalizeReferenceImageUrls(imageUrl, referenceImageUrls));
        WorkflowTuning tuning = resolveWorkflowTuning(prompt, quality, frames);
        if (hasText(extra)) {
            try {
                JsonNode extraJson = objectMapper.readTree(extra);
                JsonNode workflowNode = extraJson.path("workflow");
                if (workflowNode.isObject()) {
                    ObjectNode wf = (ObjectNode) workflowNode.deepCopy();
                    injectPromptIntoWorkflow(wf, prompt);
                    injectImagesIntoWorkflow(wf, primaryImage, uploadedReferences);
                    injectNegativePromptToWorkflow(wf);
                    injectVideoParams(wf, width, height, frames, tuning, uploadedReferences.size());
                    return wf;
                }
                String wf = extraJson.path("workflowFile").asText(null);
                // 忽略 ComfyUI 用户工作流（user:前缀），只接受 classpath 模板
                if (hasText(wf) && !wf.startsWith("user:")) {
                    workflowFile = wf;
                }
            } catch (Exception ignored) {
            }
        }

        if (!hasText(workflowFile)) {
            workflowFile = isWanModel()
                    ? "video_wan2_2_14B_i2v.json"
                    : "video_ltx2_i2v_short_drama_consistency.json";
        }

        // 优先使用 classpath 模板（非 user: 前缀以避免加载已被废弃的旧版工作流）。
        // 找不到则直接使用内联 LTX-2 I2V 工作流。
        if (hasText(workflowFile)) {
            ObjectNode template = ComfyUiWorkflowLoader.loadWorkflow(apiBaseUrl, httpClient, workflowFile);
            if (template != null) {
                injectPromptIntoWorkflow(template, prompt);
                injectImagesIntoWorkflow(template, primaryImage, uploadedReferences);
                injectNegativePromptToWorkflow(template);
                injectVideoParams(template, width, height, frames, tuning, uploadedReferences.size());
                return template;
            }
        }

        // 模板不可用 → 始终使用内联 LTX-2 I2V 工作流
        log.info("使用内联 LTX-2 I2V 工作流 (width={}, height={}, frames={})", width, height, frames);
        return buildLtx2I2vWorkflow(primaryImage, prompt, width, height, frames);
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
        saveVideoInputs.put("save_output", false);
        saveVideoInputs.put("pingpong", false);
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
                if ("WanVideoTextEncode".equals(classType) || "WanVideoTextEncodeCached".equals(classType)) {
                    JsonNode inputs = node.path("inputs");
                    if (inputs.has("positive_prompt")) {
                        ((ObjectNode) inputs).put("positive_prompt", prompt);
                        return;
                    }
                }
                if ("CLIPTextEncode".equals(classType)) {
                    JsonNode inputs = node.path("inputs");
                    if (inputs.has("text")) {
                        ((ObjectNode) node.path("inputs")).put("text", prompt);
                        return;
                    }
                }
            }
        }
        // LTX-2 template: inject into PrimitiveStringMultiline (prompt input node)
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (node.isObject()) {
                String classType = node.path("class_type").asText("");
                if ("PrimitiveStringMultiline".equals(classType)) {
                    JsonNode widgets = node.path("widgets_values");
                    if (widgets.isArray() && widgets.size() > 0 && widgets.get(0).isTextual()) {
                        ((ArrayNode) widgets).set(0, objectMapper.getNodeFactory().textNode(prompt));
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

    private void injectImagesIntoWorkflow(ObjectNode workflow, String primaryImageUrl, List<String> referenceImageUrls) {
        List<String> images = new ArrayList<>();
        if (hasText(primaryImageUrl)) {
            images.add(primaryImageUrl);
        }
        if (referenceImageUrls != null) {
            images.addAll(referenceImageUrls);
        }
        if (images.isEmpty()) {
            return;
        }

        int idx = 0;
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (!node.isObject()) {
                continue;
            }
            if (!"LoadImage".equals(node.path("class_type").asText(""))) {
                continue;
            }
            ((ObjectNode) node.path("inputs")).put("image", images.get(Math.min(idx, images.size() - 1)));
            idx++;
        }
    }

    private List<String> normalizeReferenceImageUrls(String primaryImageUrl, List<String> referenceImageUrls) {
        if (referenceImageUrls == null || referenceImageUrls.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        String primary = primaryImageUrl != null ? primaryImageUrl.trim() : "";
        for (String url : referenceImageUrls) {
            if (!hasText(url)) {
                continue;
            }
            String trimmed = url.trim();
            if (!trimmed.equals(primary)) {
                normalized.add(trimmed);
            }
            if (normalized.size() >= 5) {
                break;
            }
        }
        return new ArrayList<>(normalized);
    }

    private List<String> uploadReferenceImages(List<String> referenceImageUrls) throws Exception {
        if (referenceImageUrls == null || referenceImageUrls.isEmpty()) {
            return List.of();
        }
        List<String> uploaded = new ArrayList<>();
        for (String url : referenceImageUrls) {
            uploaded.add(uploadImageIfRemote(url));
        }
        return uploaded;
    }

    private String buildShortDramaVideoPrompt(String prompt, List<String> referenceImageUrls) {
        String base = hasText(prompt) ? prompt.trim() : "";
        if (base.contains("Commercial vertical short-drama I2V shot")
                || base.contains("首帧已确定，不重画画面")) {
            return base + " Strict video-only style lock: photorealistic live action, no sketch, no line art, no manga, no monochrome, no slideshow, no frozen frame.";
        }
        int refCount = referenceImageUrls != null ? referenceImageUrls.size() : 0;
        StringBuilder sb = new StringBuilder();
        sb.append("Commercial vertical short-drama video, one continuous 9:16 live-action shot, 4-10 seconds. ")
                .append("The input image is the exact first frame: keep the same face, hairstyle, outfit, body shape, props, lighting, camera angle, and scene layout. ")
                .append("Animate from that first frame only; do not redraw the scene as an illustration or storyboard. ");
        if (refCount > 1) {
            sb.append("Auxiliary character and scene references are consistency references only; do not create collage panels or extra characters. ");
        }
        sb.append("Shot language: slow push-in, gentle pan, or slight handheld parallax; keep it as a single camera take with no cut, no scene jump, no new person. ")
                .append("Performance: natural breathing, eye blink, small head turn, hand reaction, lip micro movement only when dialogue exists, clothing and hair micro motion. ")
                .append("Environment: subtle light shift, curtain or smoke movement, shadow flow, water or rain movement when present. ")
                .append("Temporal rhythm: clear beginning, middle, and end in the same shot; enough visible motion to avoid slideshow feeling, but no exaggerated action. ")
                .append("Strict style lock: photorealistic live action, natural skin, no sketch, no line art, no manga, no monochrome, no drawn frames. ");
        if (hasText(base)) {
            sb.append("Motion directive: ").append(base);
        }
        return sb.toString();
    }

    private record WorkflowTuning(String motionLevel,
                                  int steps,
                                  double cfg,
                                  double shift,
                                  String scheduler,
                                  double noiseAugStrength,
                                  double startLatentStrength,
                                  double endLatentStrength,
                                  double augmentEmptyFrames,
                                  double referenceStrength,
                                  double sceneReferenceStrength,
                                  double ltxCfg,
                                  String ltxSampler,
                                  String cameraMotion) {}

    private WorkflowTuning resolveWorkflowTuning(String prompt, String quality, int frames) {
        String text = hasText(prompt) ? prompt.toLowerCase() : "";
        String level;
        if (text.contains("motion intensity: high")
                || containsAny(text, "run", "chase", "fight", "奔跑", "追", "逃", "冲", "打", "推开", "摔", "转身", "跪下")) {
            level = "high";
        } else if (text.contains("motion intensity: low")
                || containsAny(text, "凝视", "沉默", "静", "微笑", "breathing", "blink")) {
            level = "low";
        } else {
            level = "medium";
        }

        boolean pro = hasText(quality) && quality.toLowerCase().contains("pro");
        int steps = pro ? 16 : 14;
        double cfg = 1.15d;
        double shift = 8.0d;
        double noise = 0.038d;
        double startStrength = 0.94d;
        double endStrength = 0.82d;
        double augment = 0.06d;
        double refStrength = 0.34d;
        double sceneRefStrength = 0.22d;
        double ltxCfg = 2.2d;

        if ("high".equals(level)) {
            steps = pro ? 18 : 16;
            cfg = 1.25d;
            shift = 8.5d;
            noise = 0.055d;
            startStrength = 0.90d;
            endStrength = 0.72d;
            augment = 0.12d;
            refStrength = 0.28d;
            sceneRefStrength = 0.18d;
            ltxCfg = 2.4d;
        } else if ("low".equals(level)) {
            steps = pro ? 14 : 12;
            cfg = 1.05d;
            shift = 7.5d;
            noise = 0.024d;
            startStrength = 0.97d;
            endStrength = 0.90d;
            augment = 0.02d;
            refStrength = 0.42d;
            sceneRefStrength = 0.28d;
            ltxCfg = 2.0d;
        }

        if (frames >= 129 && !"low".equals(level)) {
            endStrength = Math.max(0.68d, endStrength - 0.04d);
            augment = Math.min(0.16d, augment + 0.03d);
        }

        return new WorkflowTuning(
                level,
                steps,
                cfg,
                shift,
                "dpm++_sde",
                noise,
                startStrength,
                endStrength,
                augment,
                refStrength,
                sceneRefStrength,
                ltxCfg,
                "euler",
                resolveCameraMotionLabel(text, level));
    }

    private String resolveCameraMotionLabel(String text, String level) {
        if (containsAny(text, "push-in", "push in", "推进", "推近")) {
            return "slow_push_in";
        }
        if (containsAny(text, "pull-back", "pull back", "拉远")) {
            return "slow_pull_back";
        }
        if (containsAny(text, "tracking", "track", "跟拍", "横移", "移镜")) {
            return "tracking_pan";
        }
        if (containsAny(text, "tilt", "俯仰", "抬头", "低头")) {
            return "gentle_tilt";
        }
        return "high".equals(level) ? "controlled_handheld" : "slow_push_in";
    }

    private boolean containsAny(String text, String... needles) {
        if (!hasText(text)) {
            return false;
        }
        for (String needle : needles) {
            if (hasText(needle) && text.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 向工作流注入负向提示词。
     * 如果 sampler 的 positive/negative 指向同一节点，将负向提示词追加到同一个 text 字段中。
     */
    private void injectNegativePromptToWorkflow(ObjectNode workflow) {
        // 先检测 sampler 的 positive 和 negative 是否指向同一节点
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (!node.isObject()) continue;
            String classType = node.path("class_type").asText("");
            if (!isSamplerNode(classType)) continue;

            JsonNode posInput = node.path("inputs").path("positive");
            JsonNode negInput = node.path("inputs").path("negative");
            if (posInput.isArray() && negInput.isArray()
                    && posInput.size() >= 1 && negInput.size() >= 1
                    && posInput.get(0).asText("").equals(negInput.get(0).asText(""))) {
                // positive 和 negative 共享同一个节点 → 追加负向提示词
                String sharedNodeId = posInput.get(0).asText();
                JsonNode sharedNode = workflow.path(sharedNodeId);
                if (sharedNode.isObject() && sharedNode.path("inputs").path("text").isTextual()) {
                    String existing = sharedNode.path("inputs").path("text").asText("");
                    ((ObjectNode) sharedNode.path("inputs")).put("text",
                            existing + "\nNegative prompt: " + DEFAULT_NEGATIVE_PROMPT);
                    return;
                }
            }
        }

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
        int clipOutputIndex = findClipOutputIndexForNegative(workflow);
        if (clipRef == null) return;

        String negNodeId = String.valueOf(Integer.parseInt(findMaxNodeId(workflow)) + 1);
        ObjectNode negClip = workflow.putObject(negNodeId);
        negClip.put("class_type", "CLIPTextEncode");
        ObjectNode negInputs = negClip.putObject("inputs");
        negInputs.put("text", DEFAULT_NEGATIVE_PROMPT);
        ArrayNode clipArr = negInputs.putArray("clip");
        clipArr.add(clipRef);
        clipArr.add(clipOutputIndex);

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
        injectVideoParams(workflow, width, height, frames, resolveWorkflowTuning("", null, frames), 0);
    }

    private void injectVideoParams(ObjectNode workflow,
                                   int width,
                                   int height,
                                   int frames,
                                   WorkflowTuning tuning,
                                   int auxiliaryReferenceCount) {
        int fps = workflowFrameRate(workflow);
        int seed = UUID.randomUUID().hashCode() & Integer.MAX_VALUE;
        if (shouldBypassWanReferenceEmbeds()) {
            bypassWanOneToAllReferenceEmbeds(workflow);
        }
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
                case "RandomNoise":
                    inputs.put("noise_seed", seed);
                    break;
                // 潜在空间设置
                case "EmptyLatentImage":
                    inputs.put("width", width);
                    inputs.put("height", height);
                    inputs.put("batch_size", frames);
                    break;
                case "EmptyLTXVLatentVideo":
                    inputs.put("width", width);
                    inputs.put("height", height);
                    if (inputs.has("length")) {
                        inputs.put("length", frames);
                    }
                    if (inputs.has("num_frames")) {
                        inputs.put("num_frames", frames);
                    }
                    inputs.put("batch_size", 1);
                    break;
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
                    inputs.put("frame_rate", fps);
                    if (inputs.has("width")) {
                        inputs.put("width", width);
                    }
                    if (inputs.has("height")) {
                        inputs.put("height", height);
                    }
                    if (inputs.has("num_frames")) {
                        inputs.put("num_frames", frames);
                    }
                    break;
                case "CFGGuider":
                    if (inputs.has("cfg")) {
                        inputs.put("cfg", tuning.ltxCfg());
                    }
                    break;
                case "KSamplerSelect":
                    if (inputs.has("sampler_name")) {
                        inputs.put("sampler_name", tuning.ltxSampler());
                    }
                    break;
                case "BasicScheduler":
                    if (inputs.has("steps")) {
                        inputs.put("steps", Math.max(8, tuning.steps() - 4));
                    }
                    if (inputs.has("scheduler")) {
                        inputs.put("scheduler", "normal");
                    }
                    if (inputs.has("denoise")) {
                        inputs.put("denoise", "low".equals(tuning.motionLevel()) ? 0.92d : 1.0d);
                    }
                    break;
                case "KSampler":
                case "KSamplerAdvanced":
                    if (inputs.has("seed")) {
                        inputs.put("seed", seed);
                    }
                    if (inputs.has("steps")) {
                        inputs.put("steps", tuning.steps());
                    }
                    if (inputs.has("cfg")) {
                        inputs.put("cfg", Math.max(2.0d, tuning.ltxCfg()));
                    }
                    if (inputs.has("sampler_name")) {
                        inputs.put("sampler_name", "dpmpp_2m_sde");
                    }
                    if (inputs.has("scheduler")) {
                        inputs.put("scheduler", "karras");
                    }
                    if (inputs.has("denoise")) {
                        inputs.put("denoise", "low".equals(tuning.motionLevel()) ? 0.88d : 0.96d);
                    }
                    break;
                // WAN / Hunyuan sampler
                case "WanVideoSampler":
                case "WanVideoSamplerv2":
                case "LTXVScheduler":
                    if (inputs.has("seed")) {
                        inputs.put("seed", seed);
                    }
                    if (inputs.has("num_frames")) {
                        inputs.put("num_frames", frames);
                    }
                    if (inputs.has("steps")) {
                        inputs.put("steps", tuning.steps());
                    }
                    if (inputs.has("cfg")) {
                        inputs.put("cfg", tuning.cfg());
                    }
                    if (inputs.has("shift")) {
                        inputs.put("shift", tuning.shift());
                    }
                    if (inputs.has("scheduler")) {
                        inputs.put("scheduler", tuning.scheduler());
                    }
                    putIfPresent(inputs, "motion_strength", motionStrength(tuning));
                    putIfPresent(inputs, "motion_scale", motionStrength(tuning));
                    putIfPresent(inputs, "motion_guidance", motionStrength(tuning));
                    putIfPresent(inputs, "guidance_scale", tuning.cfg());
                    putIfPresent(inputs, "camera_motion", tuning.cameraMotion());
                    break;
                case "WanVideoImageToVideoEncode":
                case "WanVideoEmptyEmbeds":
                    inputs.put("width", width);
                    inputs.put("height", height);
                    inputs.put("num_frames", frames);
                    if ("WanVideoImageToVideoEncode".equals(classType)) {
                        putIfPresent(inputs, "noise_aug_strength", tuning.noiseAugStrength());
                        putIfPresent(inputs, "start_latent_strength", tuning.startLatentStrength());
                        putIfPresent(inputs, "end_latent_strength", tuning.endLatentStrength());
                        putIfPresent(inputs, "augment_empty_frames", tuning.augmentEmptyFrames());
                        putIfPresent(inputs, "motion_bucket_id", motionBucket(tuning));
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
                    putIfPresent(inputs, "strength", ltxImageStrength(tuning));
                    putIfPresent(inputs, "motion_bucket_id", motionBucket(tuning));
                    break;
            }
            injectGenericMotionControls(inputs, tuning);
        }
        bindWanAdvancedControlNodes(workflow, auxiliaryReferenceCount, tuning);
        rebalanceWanSamplerSteps(workflow, tuning.steps());
        log.debug("注入视频参数: width={}, height={}, frames={}, fps={}, motionLevel={}, steps={}, cfg={}, shift={}",
                width, height, frames, fps, tuning.motionLevel(), tuning.steps(), tuning.cfg(), tuning.shift());
    }

    private void putIfPresent(ObjectNode inputs, String fieldName, double value) {
        if (inputs.has(fieldName)) {
            inputs.put(fieldName, value);
        }
    }

    private void putIfPresent(ObjectNode inputs, String fieldName, int value) {
        if (inputs.has(fieldName)) {
            inputs.put(fieldName, value);
        }
    }

    private void putIfPresent(ObjectNode inputs, String fieldName, String value) {
        if (inputs.has(fieldName) && hasText(value)) {
            inputs.put(fieldName, value);
        }
    }

    private double motionStrength(WorkflowTuning tuning) {
        return switch (tuning.motionLevel()) {
            case "high" -> 1.35d;
            case "low" -> 0.82d;
            default -> 1.08d;
        };
    }

    private int motionBucket(WorkflowTuning tuning) {
        return switch (tuning.motionLevel()) {
            case "high" -> 160;
            case "low" -> 88;
            default -> 124;
        };
    }

    private double ltxImageStrength(WorkflowTuning tuning) {
        return switch (tuning.motionLevel()) {
            case "high" -> 0.88d;
            case "low" -> 0.97d;
            default -> 0.93d;
        };
    }

    private void injectGenericMotionControls(ObjectNode inputs, WorkflowTuning tuning) {
        putIfPresent(inputs, "motion_strength", motionStrength(tuning));
        putIfPresent(inputs, "motion_scale", motionStrength(tuning));
        putIfPresent(inputs, "motion_guidance", motionStrength(tuning));
        putIfPresent(inputs, "camera_motion", tuning.cameraMotion());
        putIfPresent(inputs, "camera_move", tuning.cameraMotion());
        putIfPresent(inputs, "control_strength", tuning.referenceStrength());
        putIfPresent(inputs, "guidance_scale", tuning.cfg());
    }

    private void bindWanAdvancedControlNodes(ObjectNode workflow, int auxiliaryReferenceCount, WorkflowTuning tuning) {
        if (shouldBypassWanReferenceEmbeds() || auxiliaryReferenceCount <= 0) {
            return;
        }

        String baseEmbedNodeId = findFirstNodeId(workflow, "WanVideoImageToVideoEncode", "WanVideoEmptyEmbeds");
        if (!hasText(baseEmbedNodeId)) {
            return;
        }

        List<String> patchNodeIds = new ArrayList<>();
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (node.isObject() && isWanReferencePatchNode(node.path("class_type").asText(""))) {
                patchNodeIds.add(entry.getKey());
            }
        }
        if (patchNodeIds.isEmpty()) {
            return;
        }

        String currentEmbedNodeId = baseEmbedNodeId;
        int used = 0;
        for (String nodeId : patchNodeIds) {
            JsonNode node = workflow.path(nodeId);
            if (!node.isObject()) {
                continue;
            }
            ObjectNode inputs = (ObjectNode) node.path("inputs");
            ArrayNode embeds = inputs.putArray("embeds");
            embeds.add(currentEmbedNodeId);
            embeds.add(0);
            if (inputs.has("strength")) {
                inputs.put("strength", used == 0 ? tuning.referenceStrength() : tuning.sceneReferenceStrength());
            }
            putIfPresent(inputs, "start_percent", 0.0d);
            putIfPresent(inputs, "end_percent", "high".equals(tuning.motionLevel()) ? 0.82d : 1.0d);
            currentEmbedNodeId = nodeId;
            used++;
            if (used >= Math.min(auxiliaryReferenceCount, patchNodeIds.size())) {
                break;
            }
        }

        if (used <= 0 || currentEmbedNodeId.equals(baseEmbedNodeId)) {
            return;
        }

        for (var it = workflow.fields(); it.hasNext(); ) {
            JsonNode node = it.next().getValue();
            if (!node.isObject()) {
                continue;
            }
            String classType = node.path("class_type").asText("");
            if ("WanVideoSampler".equals(classType) || "WanVideoSamplerv2".equals(classType)) {
                ObjectNode inputs = (ObjectNode) node.path("inputs");
                ArrayNode imageEmbeds = inputs.putArray("image_embeds");
                imageEmbeds.add(currentEmbedNodeId);
                imageEmbeds.add(0);
            }
        }
        log.debug("已连接 Wan 参考控制节点: base={}, final={}, refCount={}", baseEmbedNodeId, currentEmbedNodeId, auxiliaryReferenceCount);
    }

    private void rebalanceWanSamplerSteps(ObjectNode workflow, int steps) {
        List<ObjectNode> samplers = new ArrayList<>();
        for (var it = workflow.fields(); it.hasNext(); ) {
            JsonNode node = it.next().getValue();
            if (node.isObject()) {
                String classType = node.path("class_type").asText("");
                if ("WanVideoSampler".equals(classType) || "WanVideoSamplerv2".equals(classType)) {
                    samplers.add((ObjectNode) node);
                }
            }
        }
        if (samplers.size() < 2) {
            return;
        }
        int split = Math.max(1, steps / 2);
        ObjectNode firstInputs = (ObjectNode) samplers.get(0).path("inputs");
        ObjectNode secondInputs = (ObjectNode) samplers.get(1).path("inputs");
        if (firstInputs.has("start_step")) {
            firstInputs.put("start_step", 0);
        }
        if (firstInputs.has("end_step")) {
            firstInputs.put("end_step", split);
        }
        if (secondInputs.has("start_step")) {
            secondInputs.put("start_step", split);
        }
        if (secondInputs.has("end_step")) {
            secondInputs.put("end_step", -1);
        }
    }

    private String findFirstNodeId(ObjectNode workflow, String... classTypes) {
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (!node.isObject()) {
                continue;
            }
            String classType = node.path("class_type").asText("");
            for (String expected : classTypes) {
                if (expected.equals(classType)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private boolean shouldBypassWanReferenceEmbeds() {
        if (!hasText(extra)) {
            return false;
        }
        try {
            JsonNode extraJson = objectMapper.readTree(extra);
            return extraJson.path("bypassWanReferenceEmbeds").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void bypassWanOneToAllReferenceEmbeds(ObjectNode workflow) {
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (!node.isObject()) {
                continue;
            }
            String classType = node.path("class_type").asText("");
            ObjectNode inputs = (ObjectNode) node.path("inputs");
            if ("WanVideoSampler".equals(classType) || "WanVideoSamplerv2".equals(classType)) {
                replaceWanEmbedLinkIfNeeded(workflow, inputs, "image_embeds", entry.getKey());
            } else if (isWanReferencePatchNode(classType)) {
                replaceWanEmbedLinkIfNeeded(workflow, inputs, "embeds", entry.getKey());
            }
        }
    }

    private void replaceWanEmbedLinkIfNeeded(ObjectNode workflow, ObjectNode inputs, String fieldName, String nodeId) {
        JsonNode link = inputs.path(fieldName);
        JsonNode resolved = resolveWanBaseEmbedLink(workflow, link);
        if (resolved == link || !resolved.isArray() || resolved.size() < 2) {
            return;
        }
        ArrayNode replacement = inputs.putArray(fieldName);
        replacement.add(resolved.get(0).asText());
        replacement.add(resolved.get(1).asInt());
        log.info("已绕过 Wan 参考增强节点以兼容当前 WanVideoWrapper: node={}, field={}", nodeId, fieldName);
    }

    private JsonNode resolveWanBaseEmbedLink(ObjectNode workflow, JsonNode link) {
        JsonNode current = link;
        Set<String> visited = new LinkedHashSet<>();
        while (current.isArray() && current.size() >= 2) {
            String upstreamId = current.get(0).asText("");
            if (!visited.add(upstreamId)) {
                return current;
            }
            JsonNode upstream = workflow.path(upstreamId);
            String upstreamClass = upstream.path("class_type").asText("");
            if (!isWanReferencePatchNode(upstreamClass)) {
                return current;
            }
            JsonNode next = upstream.path("inputs").path("embeds");
            if (!next.isArray() || next.size() < 2) {
                return current;
            }
            current = next;
        }
        return current;
    }

    private boolean isWanReferencePatchNode(String classType) {
        return "WanVideoAddOneToAllReferenceEmbeds".equals(classType)
                || "WanVideoAddOneToAllPoseEmbeds".equals(classType)
                || "WanVideoAddSCAILReferenceEmbeds".equals(classType)
                || "WanVideoAddSCAILPoseEmbeds".equals(classType);
    }

    private int workflowFrameRate(ObjectNode workflow) {
        if (workflowContainsClass(workflow, "LTXVConditioning")) {
            return 24;
        }
        if (workflowContainsClass(workflow, "WanVideoImageToVideoEncode")
                || workflowContainsClass(workflow, "WanVideoSampler")) {
            return 16;
        }
        return 8;
    }

    private boolean workflowContainsClass(ObjectNode workflow, String classType) {
        for (var it = workflow.fields(); it.hasNext(); ) {
            JsonNode node = it.next().getValue();
            if (node.isObject() && classType.equals(node.path("class_type").asText(""))) {
                return true;
            }
        }
        return false;
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

    private int findClipOutputIndexForNegative(ObjectNode workflow) {
        for (var it = workflow.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode node = entry.getValue();
            if (!node.isObject()) continue;
            String classType = node.path("class_type").asText("");
            if ("CLIPTextEncode".equals(classType)) {
                JsonNode clip = node.path("inputs").path("clip");
                if (clip.isArray() && clip.size() >= 2 && clip.get(1).canConvertToInt()) {
                    return clip.get(1).asInt();
                }
            }
        }
        return 1;
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

    private record OutputInfo(String filename, String type, String subfolder) {}

    private String buildOutputViewUrl(OutputInfo info) {
        StringBuilder sb = new StringBuilder(apiBaseUrl);
        sb.append("/view?filename=").append(URLEncoder.encode(info.filename(), StandardCharsets.UTF_8));
        sb.append("&type=").append(hasText(info.type()) ? info.type() : "output");
        if (hasText(info.subfolder())) {
            sb.append("&subfolder=").append(URLEncoder.encode(info.subfolder(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private OutputInfo pollForResult(String promptId) throws Exception {
        String historyUrl = apiBaseUrl + "/history/" + promptId;
        int attempt = 0;
        while (true) {
            Thread.sleep(pollIntervalMs);
            attempt++;
            if (attempt > maxPollAttempts) {
                String queueStatus = checkQueueStatus(promptId);
                if ("running".equals(queueStatus) || "pending".equals(queueStatus)) {
                    throw new RuntimeException("ComfyUI 视频任务仍在执行中 (prompt_id="
                            + promptId + ", queueStatus=" + queueStatus + ")，已达到等待上限");
                }
                if ("not_found".equals(queueStatus)) {
                    throw new RuntimeException("ComfyUI 视频任务已不在队列中，可能已被清理 (prompt_id=" + promptId + ")");
                }
                throw new RuntimeException("ComfyUI 视频生成等待超时 (prompt_id="
                        + promptId + ", queueStatus=" + queueStatus + ")");
            }

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
                        JsonNode first = gifs.get(0);
                        String filename = first.path("filename").asText(null);
                        if (hasText(filename)) {
                            String type = first.path("type").asText("output");
                            String subfolder = first.path("subfolder").asText("");
                            log.info("ComfyUI 视频生成完成, filename={}, type={}, subfolder={}", filename, type, subfolder);
                            return new OutputInfo(filename, type, subfolder);
                        }
                    }
                    JsonNode videos = entry.getValue().path("videos");
                    if (videos.isArray() && videos.size() > 0) {
                        JsonNode first = videos.get(0);
                        String filename = first.path("filename").asText(null);
                        if (hasText(filename)) {
                            String type = first.path("type").asText("output");
                            String subfolder = first.path("subfolder").asText("");
                            log.info("ComfyUI 视频生成完成, filename={}, type={}, subfolder={}", filename, type, subfolder);
                            return new OutputInfo(filename, type, subfolder);
                        }
                    }
                    JsonNode images = entry.getValue().path("images");
                    if (images.isArray() && images.size() > 0) {
                        JsonNode first = images.get(0);
                        String filename = first.path("filename").asText(null);
                        if (hasText(filename)) {
                            String type = first.path("type").asText("output");
                            String subfolder = first.path("subfolder").asText("");
                            log.info("ComfyUI 输出完成, filename={}, type={}, subfolder={}", filename, type, subfolder);
                            return new OutputInfo(filename, type, subfolder);
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
        return "ltx-2-19b-distilled.safetensors";
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!hasText(baseUrl)) {
            return "http://127.0.0.1:8188";
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
