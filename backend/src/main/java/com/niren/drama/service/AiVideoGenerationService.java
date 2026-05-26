package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.AiResolvedConfig;
import com.niren.drama.ai.VideoAiProvider;
import com.niren.drama.ai.trace.AiTraceSupport;
import com.niren.drama.common.ProjectStyleSupport;
import com.niren.drama.entity.Character;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Scene;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.SceneMapper;
import com.niren.drama.mapper.StoryboardMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiVideoGenerationService {

    private static final Duration BLOCKING_POLL_TIMEOUT = Duration.ofMinutes(10);
    private static final long POLL_INTERVAL_MS = 3000L;

    private final AiProviderFactory aiProviderFactory;
    private final ProjectService projectService;
    private final CharacterMapper characterMapper;
    private final SceneMapper sceneMapper;
    private final StoryboardMapper storyboardMapper;
    private final PublicAssetStorageService publicAssetStorageService;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

        public record VideoTaskSubmission(String provider, String taskId, String statusUrl, String videoUrl) {}

        public record VideoTaskQueryResult(String provider, String status, String videoUrl, String errorMessage) {}

    public String generateVideo(Long userId, Storyboard shot) {
        AiResolvedConfig config = aiProviderFactory.resolveConfig(userId, "video");
        log.debug("Resolve video provider for shot: userId={}, shotId={}, shotNo={}, provider={}, model={}, hasImageUrl={}",
                userId,
                shot.getId(),
                shot.getShotNo(),
                config.provider(),
                config.model(),
                hasText(shot.getImageUrl()));
        if (isAliyunProvider(config.provider())) {
            return generateAliyunVideo(config, shot);
        }
        if (isComfyUiProvider(config.provider())) {
            VideoTaskSubmission submission = submitComfyUiVideoTask(userId, config, shot);
            if (hasText(submission.videoUrl())) {
                return submission.videoUrl();
            }
            throw new RuntimeException("ComfyUI 视频生成未返回视频地址");
        }
        return generateCustomVideo(config, shot);
    }

    public VideoTaskSubmission submitVideoTask(Long userId, Storyboard shot) {
        AiResolvedConfig config = aiProviderFactory.resolveConfig(userId, "video");
        log.debug("Submit video task: userId={}, shotId={}, shotNo={}, provider={}, model={}, hasImageUrl={}",
                userId,
                shot.getId(),
                shot.getShotNo(),
                config.provider(),
                config.model(),
                hasText(shot.getImageUrl()));
        if (isAliyunProvider(config.provider())) {
            return submitAliyunVideoTask(config, shot);
        }
        if (isComfyUiProvider(config.provider())) {
            return submitComfyUiVideoTask(userId, config, shot);
        }
        return submitCustomVideoTask(config, shot);
    }

    public VideoTaskQueryResult querySubmittedVideoTask(Long userId, Storyboard shot) {
        AiResolvedConfig config = aiProviderFactory.resolveConfig(userId, "video");
        String provider = hasText(shot.getVideoTaskProvider()) ? shot.getVideoTaskProvider() : config.provider();
        boolean isComfyUi = "comfyui".equalsIgnoreCase(provider);
        if (!isComfyUi && !hasText(config.apiKey())) {
            throw new BusinessException("未配置视频生成 API Key，请先在 AI 配置中设置视频服务");
        }
        String statusUrl = resolveSubmittedTaskStatusUrl(provider, config.baseUrl(), shot.getVideoTaskStatusUrl(), shot.getVideoTaskId());
        if (!hasText(statusUrl)) {
            throw new BusinessException("视频任务缺少 taskId 或状态查询地址，无法继续轮询");
        }
        log.debug("Query submitted video task: shotId={}, shotNo={}, provider={}, statusUrl={}",
                shot.getId(), shot.getShotNo(), provider, statusUrl);
        try {
            return queryVideoTask(provider, config.apiKey(), statusUrl);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("视频任务查询失败: " + e.getMessage(), e);
        }
    }

    public VideoTaskSubmission submitReferenceVideoTask(Long userId,
                                                        String prompt,
                                                        String referenceImageUrl,
                                                        Integer duration) {
        AiResolvedConfig config = aiProviderFactory.resolveConfig(userId, "video");
        String effectivePrompt = hasText(prompt) ? prompt.trim() : null;
        String effectiveReferenceImageUrl = publicAssetStorageService.ensurePublicUrl(referenceImageUrl, "reference-images", "png");
        if (!hasText(effectivePrompt)) {
            throw new BusinessException("视频 prompt 不能为空");
        }
        if (!hasText(effectiveReferenceImageUrl)) {
            throw new BusinessException("参考图不能为空，请先上传到 COS 或提供可访问图片链接");
        }
        int effectiveDuration = duration != null && duration > 0 ? duration : 5;
        if (isAliyunProvider(config.provider())) {
            return submitAliyunVideoTask(config, effectivePrompt, effectiveReferenceImageUrl, effectiveDuration, null);
        }
        return submitCustomVideoTask(config, effectivePrompt, effectiveReferenceImageUrl, effectiveDuration, null);
    }

    public VideoTaskQueryResult queryReferenceVideoTask(Long userId, String taskId, String statusUrl) {
        AiResolvedConfig config = aiProviderFactory.resolveConfig(userId, "video");
        boolean isComfyUi = "comfyui".equalsIgnoreCase(config.provider());
        if (!isComfyUi && !hasText(config.apiKey())) {
            throw new BusinessException("未配置视频生成 API Key，请先在 AI 配置中设置视频服务");
        }
        String resolvedStatusUrl = resolveSubmittedTaskStatusUrl(config.provider(), config.baseUrl(), statusUrl, taskId);
        if (!hasText(resolvedStatusUrl)) {
            throw new BusinessException("缺少 taskId 或 statusUrl，无法查询视频任务");
        }
        try {
            return queryVideoTask(config.provider(), config.apiKey(), resolvedStatusUrl);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("视频任务查询失败: " + e.getMessage(), e);
        }
    }

    private String generateAliyunVideo(AiResolvedConfig config, Storyboard shot) {
        VideoTaskSubmission submission = submitAliyunVideoTask(config, shot);
        if (hasText(submission.videoUrl())) {
            return submission.videoUrl();
        }
        String statusUrl = resolveSubmittedTaskStatusUrl(submission.provider(), config.baseUrl(), submission.statusUrl(), submission.taskId());
        if (!hasText(statusUrl)) {
            throw new RuntimeException("视频接口未返回可用视频地址，也未提供任务查询地址");
        }
        try {
            return waitForVideoResult(submission.provider(), config.apiKey(), statusUrl);
        } catch (Exception e) {
            throw new RuntimeException("视频生成失败: " + e.getMessage(), e);
        }
    }

    private VideoTaskSubmission submitAliyunVideoTask(AiResolvedConfig config, Storyboard shot) {
        String prompt = resolvePrompt(shot);
        String referenceImageUrl = resolveReferenceImageUrl(shot);
        try {
            return submitAliyunVideoTask(config, prompt, referenceImageUrl, resolveDuration(shot), shot);
        } catch (RuntimeException ex) {
            maybeDowngradeToTierB(shot, ex.getMessage());
            throw ex;
        }
    }

    private VideoTaskSubmission submitAliyunVideoTask(AiResolvedConfig config,
                                                      String prompt,
                                                      String referenceImageUrl,
                                                      int duration,
                                                      Storyboard shot) {
        if (!hasText(config.apiKey())) {
            throw new BusinessException("未配置视频生成 API Key，请先在 AI 配置中设置视频服务");
        }
        if (!hasText(prompt)) {
            throw new BusinessException("动态镜头缺少视频提示词，无法发起阿里云视频接口");
        }

        String effectiveReferenceImageUrl = publicAssetStorageService.ensurePublicUrl(referenceImageUrl, "reference-images", "png");
        if (!hasText(effectiveReferenceImageUrl)) {
            throw new BusinessException("阿里云万相 2.7 图生视频缺少可访问参考图，请先生成分镜图或角色图并上传 COS");
        }

        String resolvedModel = resolveAliyunVideoModel(config.model(), effectiveReferenceImageUrl);
        VideoGenerationProfile profile = resolveVideoGenerationProfile(shot, duration);

        String endpoint = resolveAliyunVideoEndpoint(config.baseUrl());
        log.debug("Start aliyun video generation: shotId={}, shotNo={}, endpoint={}, model={}, promptLength={}",
                shot != null ? shot.getId() : null,
                shot != null ? shot.getShotNo() : null,
                endpoint,
                resolvedModel,
                prompt.length());
        String requestBody = null;
        HttpResponse<String> response = null;
        String responseBody = null;
        String videoUrl = null;
        String taskId = null;
        String statusUrl = null;
        String error = null;
        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "Authorization", AiTraceSupport.maskBearer(config.apiKey()),
                "X-DashScope-Async", "enable");

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", resolvedModel);

            ObjectNode input = body.putObject("input");
            input.put("prompt", prompt);
            if (requiresInputMediaField(resolvedModel)) {
                ObjectNode mediaItem = objectMapper.createObjectNode();
                mediaItem.put("type", "first_frame");
                mediaItem.put("url", effectiveReferenceImageUrl);
                input.putArray("media").add(mediaItem);
            } else {
                input.put("img_url", effectiveReferenceImageUrl);
            }

            ObjectNode parameters = body.putObject("parameters");
            parameters.put("resolution", profile.resolution());
            parameters.put("ratio", "9:16");
            parameters.put("prompt_extend", true);
            parameters.put("watermark", true);
            parameters.put("duration", profile.durationSeconds());
            parameters.put("quality", profile.qualityTier());

            requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("X-DashScope-Async", "enable")
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
            videoUrl = extractVideoUrl(root);
            if (hasText(videoUrl)) {
                videoUrl = persistVideoUrl(videoUrl);
                log.debug("Aliyun video generation returned direct url: shotId={}, shotNo={}, videoUrl={}",
                        shot != null ? shot.getId() : null,
                        shot != null ? shot.getShotNo() : null,
                        videoUrl);
                return new VideoTaskSubmission(config.provider(), null, null, videoUrl);
            }

            taskId = extractTaskId(root);
            statusUrl = resolveAliyunStatusUrl(endpoint, root);
            if (!hasText(statusUrl) && hasText(taskId)) {
                statusUrl = normalizeAliyunApiBase(config.baseUrl()) + "/tasks/" + taskId;
            }

            log.debug("Aliyun video generation accepted async task: shotId={}, shotNo={}, taskId={}, statusUrl={}",
            shot != null ? shot.getId() : null,
            shot != null ? shot.getShotNo() : null,
            taskId,
            statusUrl);

            if (!hasText(statusUrl) && !hasText(taskId)) {
                error = "视频接口未返回可用视频地址，也未提供任务查询地址: " + responseBody;
                throw new RuntimeException(error);
            }
            return new VideoTaskSubmission(config.provider(), taskId, statusUrl, null);
        } catch (Exception e) {
            if (!hasText(error)) {
                error = e.getMessage();
            }
            log.error("Video generation failed for shot {}", shot != null ? shot.getShotNo() : null, e);
            throw new RuntimeException("视频生成失败: " + error, e);
        } finally {
            AiTraceSupport.record(
                    "video",
                    config.provider(),
                    "generate_video",
                    "POST",
                    endpoint,
                    headers,
                    requestBody,
                    response != null ? response.statusCode() : null,
                    response != null ? response.headers().firstValue("Content-Type").orElse(null) : null,
                    responseBody,
                    responseBody != null ? responseBody.length() : null,
                    response != null && response.statusCode() < 400 && (hasText(videoUrl) || hasText(taskId) || hasText(statusUrl)),
                    hasText(videoUrl) ? videoUrl : effectiveReferenceImageUrl,
                    error);
        }
    }

    private String generateCustomVideo(AiResolvedConfig config, Storyboard shot) {
        VideoTaskSubmission submission = submitCustomVideoTask(config, shot);
        if (hasText(submission.videoUrl())) {
            return submission.videoUrl();
        }
        String statusUrl = resolveSubmittedTaskStatusUrl(submission.provider(), config.baseUrl(), submission.statusUrl(), submission.taskId());
        if (!hasText(statusUrl)) {
            throw new RuntimeException("视频接口未返回可用视频地址，也未提供任务查询地址");
        }
        try {
            return waitForVideoResult(submission.provider(), config.apiKey(), statusUrl);
        } catch (Exception e) {
            throw new RuntimeException("视频生成失败: " + e.getMessage(), e);
        }
    }

    private VideoTaskSubmission submitCustomVideoTask(AiResolvedConfig config, Storyboard shot) {
        String prompt = resolvePrompt(shot);
        String referenceImageUrl = resolveReferenceImageUrl(shot);
        List<String> referenceImageUrls = resolveReferenceImageUrls(shot);
        try {
            return submitCustomVideoTask(config, prompt, referenceImageUrl, referenceImageUrls, resolveDuration(shot), shot);
        } catch (RuntimeException ex) {
            maybeDowngradeToTierB(shot, ex.getMessage());
            throw ex;
        }
    }

    private VideoTaskSubmission submitCustomVideoTask(AiResolvedConfig config,
                                                      String prompt,
                                                      String referenceImageUrl,
                                                      int duration,
                                                      Storyboard shot) {
        return submitCustomVideoTask(config, prompt, referenceImageUrl,
                hasText(referenceImageUrl) ? List.of(referenceImageUrl) : List.of(),
                duration,
                shot);
    }

    private VideoTaskSubmission submitCustomVideoTask(AiResolvedConfig config,
                                                      String prompt,
                                                      String referenceImageUrl,
                                                      List<String> referenceImageUrls,
                                                      int duration,
                                                      Storyboard shot) {
        boolean isComfyUi = "comfyui".equalsIgnoreCase(config.provider());
        if (!isComfyUi && !hasText(config.apiKey())) {
            throw new BusinessException("未配置自定义视频接口 API Key");
        }
        if (!hasText(prompt)) {
            throw new BusinessException("动态镜头缺少视频提示词，无法发起自定义视频接口");
        }
        List<String> effectiveReferenceImageUrls = normalizeReferenceImageUrls(referenceImageUrls, referenceImageUrl);
        String effectiveReferenceImageUrl = effectiveReferenceImageUrls.isEmpty() ? null : effectiveReferenceImageUrls.get(0);

        String endpoint = resolveCustomVideoEndpoint(config.baseUrl());
        VideoGenerationProfile profile = resolveVideoGenerationProfile(shot, duration);
        log.debug("Start custom video generation: shotId={}, shotNo={}, endpoint={}, model={}, promptLength={}, hasImage={}",
            shot != null ? shot.getId() : null,
            shot != null ? shot.getShotNo() : null,
            endpoint,
            config.model(),
            prompt.length(),
            hasText(effectiveReferenceImageUrl));
        String requestBody = null;
        HttpResponse<String> response = null;
        String responseBody = null;
        String videoUrl = null;
        String taskId = null;
        String statusUrl = null;
        String error = null;
        Map<String, String> headers = AiTraceSupport.jsonHeaders(config.apiKey());

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", config.model());
            body.put("prompt", prompt);
            if (hasText(effectiveReferenceImageUrl)) {
                body.put("image_url", effectiveReferenceImageUrl);
            }
            if (!effectiveReferenceImageUrls.isEmpty()) {
                body.putArray("image_urls").addAll(effectiveReferenceImageUrls.stream()
                        .map(objectMapper.getNodeFactory()::textNode)
                        .toList());
                body.putArray("reference_images").addAll(effectiveReferenceImageUrls.stream()
                        .map(objectMapper.getNodeFactory()::textNode)
                        .toList());
            }
            body.put("duration", profile.durationSeconds());
            body.put("size", "A".equalsIgnoreCase(resolveMotionTier(shot)) ? "1080x1920" : "720x1280");
            body.put("quality", profile.qualityTier());
            body.put("with_sound", false);

            requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
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
            videoUrl = extractVideoUrl(root);
            if (hasText(videoUrl)) {
                videoUrl = persistVideoUrl(videoUrl);
                log.debug("Custom video generation returned direct url: shotId={}, shotNo={}, videoUrl={}",
                        shot != null ? shot.getId() : null,
                        shot != null ? shot.getShotNo() : null,
                        videoUrl);
                return new VideoTaskSubmission(config.provider(), null, null, videoUrl);
            }

            taskId = extractTaskId(root);
            statusUrl = resolveGenericStatusUrl(endpoint, root);
            if (!hasText(statusUrl) && hasText(taskId)) {
                statusUrl = resolveGenericTaskEndpoint(endpoint, taskId);
            }

            log.debug("Custom video generation accepted async task: shotId={}, shotNo={}, taskId={}, statusUrl={}",
            shot != null ? shot.getId() : null,
            shot != null ? shot.getShotNo() : null,
            taskId,
            statusUrl);

            if (!hasText(statusUrl) && !hasText(taskId)) {
                error = "视频接口未返回可用视频地址，也未提供任务查询地址: " + responseBody;
                throw new RuntimeException(error);
            }
            return new VideoTaskSubmission(config.provider(), taskId, statusUrl, null);
        } catch (Exception e) {
            if (!hasText(error)) {
                error = e.getMessage();
            }
            throw new RuntimeException("视频生成失败: " + error, e);
        } finally {
            AiTraceSupport.record(
                    "video",
                    config.provider(),
                    "generate_video",
                    "POST",
                    endpoint,
                    headers,
                    requestBody,
                    response != null ? response.statusCode() : null,
                    response != null ? response.headers().firstValue("Content-Type").orElse(null) : null,
                    responseBody,
                    responseBody != null ? responseBody.length() : null,
                    response != null && response.statusCode() < 400 && (hasText(videoUrl) || hasText(taskId) || hasText(statusUrl)),
                    hasText(videoUrl) ? videoUrl : effectiveReferenceImageUrl,
                    error);
        }
    }

    private List<String> normalizeReferenceImageUrls(List<String> imageUrls, String fallbackImageUrl) {
        Set<String> normalized = new LinkedHashSet<>();
        if (imageUrls != null) {
            for (String imageUrl : imageUrls) {
                addPublicReference(normalized, imageUrl);
            }
        }
        addPublicReference(normalized, fallbackImageUrl);
        return new ArrayList<>(normalized);
    }

    private String waitForVideoResult(String provider, String apiKey, String statusUrl) throws Exception {
        long deadline = System.nanoTime() + BLOCKING_POLL_TIMEOUT.toNanos();
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            attempt++;
            Thread.sleep(POLL_INTERVAL_MS);
            VideoTaskQueryResult result = queryVideoTask(provider, apiKey, statusUrl);
            log.debug("Video polling attempt: provider={}, statusUrl={}, attempt={}, status={}, hasVideoUrl={}",
                    provider, statusUrl, attempt, result.status(), hasText(result.videoUrl()));
            if (hasText(result.videoUrl()) && (isSuccessStatus(result.status()) || !hasText(result.status()))) {
                return result.videoUrl();
            }
            if (isFailureStatus(result.status())) {
                throw new RuntimeException(hasText(result.errorMessage()) ? result.errorMessage() : "视频生成任务失败");
            }
            if (isSuccessStatus(result.status()) && !hasText(result.videoUrl())) {
                throw new RuntimeException("视频任务已成功，但响应中没有可用视频地址");
            }
        }
        throw new RuntimeException("视频生成任务轮询超时，已等待至少10分钟，请稍后重试");
    }

    private VideoTaskQueryResult queryVideoTask(String provider, String apiKey, String statusUrl) throws Exception {
        HttpResponse<String> response = null;
        String responseBody = null;
        String videoUrl = null;
        String status = null;
        String error = null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(60))
                    .build();

            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            responseBody = response.body();
            JsonNode root = hasText(responseBody) ? objectMapper.readTree(responseBody) : objectMapper.createObjectNode();
            videoUrl = extractVideoUrl(root);
            if (hasText(videoUrl)) {
                videoUrl = persistVideoUrl(videoUrl);
            }
            status = normalizeStatus(extractStatus(root));
            if (response.statusCode() >= 400) {
                error = "HTTP " + response.statusCode() + " - " + responseBody;
                throw new RuntimeException(error);
            }
            if (isFailureStatus(status)) {
                error = extractErrorMessage(root);
            }
            return new VideoTaskQueryResult(provider, status, videoUrl, error);
        } finally {
            AiTraceSupport.record(
                    "video",
                    provider,
                    "poll_video_task",
                    "GET",
                    statusUrl,
                    Map.of("Authorization", AiTraceSupport.maskBearer(apiKey)),
                    null,
                    response != null ? response.statusCode() : null,
                    response != null ? response.headers().firstValue("Content-Type").orElse(null) : null,
                    responseBody,
                    responseBody != null ? responseBody.length() : null,
                    response != null && response.statusCode() < 400 && (hasText(videoUrl) || isPendingStatus(status) || isSuccessStatus(status)),
                    videoUrl,
                    error);
        }
    }

    private String resolveSubmittedTaskStatusUrl(String provider, String baseUrl, String statusUrl, String taskId) {
        if (hasText(statusUrl)) {
            return statusUrl;
        }
        if (!hasText(taskId)) {
            return null;
        }
        if (isAliyunProvider(provider)) {
            return normalizeAliyunApiBase(baseUrl) + "/tasks/" + taskId;
        }
        return resolveGenericTaskEndpoint(resolveCustomVideoEndpoint(baseUrl), taskId);
    }

    private String resolveCustomVideoEndpoint(String baseUrl) {
        if (!hasText(baseUrl)) {
            throw new BusinessException("视频接口 Base URL 不能为空");
        }
        String normalized = trimTrailingSlash(baseUrl.trim());
        if (normalized.contains("/videos/") || normalized.contains("/video/")) {
            return normalized;
        }
        if (normalized.endsWith("/videos") || normalized.endsWith("/video")) {
            return normalized + "/generations";
        }
        return normalized + "/videos/generations";
    }

    private String resolveGenericTaskEndpoint(String endpoint, String taskId) {
        String normalized = trimTrailingSlash(endpoint);
        if (normalized.endsWith("/videos/generations")) {
            normalized = normalized.substring(0, normalized.length() - "/videos/generations".length());
        } else if (normalized.endsWith("/video/generations")) {
            normalized = normalized.substring(0, normalized.length() - "/video/generations".length());
        }
        return normalized + "/tasks/" + taskId;
    }

    private String resolveAliyunStatusUrl(String endpoint, JsonNode root) {
        String direct = firstText(
                root.path("output").path("task_status_url"),
                root.path("output").path("taskStatusUrl"),
                root.path("output").path("status_url"),
                root.path("output").path("statusUrl"),
                root.path("task_status_url"),
                root.path("taskStatusUrl"));
        if (hasText(direct)) {
            return direct.startsWith("http") ? direct : resolveRelativeUrl(endpoint, direct);
        }
        return null;
    }

    private String resolveGenericStatusUrl(String endpoint, JsonNode root) {
        String direct = firstText(
                root.path("status_url"),
                root.path("statusUrl"),
                root.path("task_url"),
                root.path("taskUrl"),
                root.path("data").path("status_url"),
                root.path("data").path("statusUrl"),
                root.path("output").path("status_url"),
                root.path("output").path("statusUrl"));
        if (hasText(direct)) {
            return direct.startsWith("http") ? direct : resolveRelativeUrl(endpoint, direct);
        }
        return null;
    }

    private String resolveRelativeUrl(String endpoint, String path) {
        String normalizedEndpoint = trimTrailingSlash(endpoint);
        int protocolIndex = normalizedEndpoint.indexOf("://");
        if (protocolIndex < 0) {
            return path;
        }
        int firstSlash = normalizedEndpoint.indexOf('/', protocolIndex + 3);
        String origin = firstSlash > 0 ? normalizedEndpoint.substring(0, firstSlash) : normalizedEndpoint;
        if (path.startsWith("/")) {
            return origin + path;
        }
        return origin + "/" + path;
    }

    private String extractVideoUrl(JsonNode root) {
        return firstText(
                root.path("url"),
                root.path("video_url"),
                root.path("videoUrl"),
                root.path("download_url"),
                root.path("downloadUrl"),
                root.path("result").path("url"),
                root.path("result").path("video_url"),
                root.path("result").path("videoUrl"),
                root.path("output").path("url"),
                root.path("output").path("video_url"),
                root.path("output").path("videoUrl"),
                root.path("data").path("url"),
                root.path("data").path("video_url"),
                root.path("data").path("videoUrl"),
                root.path("output").path("results").isArray() && root.path("output").path("results").size() > 0 ? root.path("output").path("results").get(0).path("video_url") : null,
                root.path("data").isArray() && root.path("data").size() > 0 ? root.path("data").get(0).path("url") : null,
                root.path("output").path("results").isArray() && root.path("output").path("results").size() > 0 ? root.path("output").path("results").get(0).path("url") : null);
    }

    private String extractTaskId(JsonNode root) {
        return firstText(
                root.path("task_id"),
                root.path("taskId"),
                root.path("data").path("task_id"),
                root.path("data").path("taskId"),
                root.path("output").path("task_id"),
                root.path("output").path("taskId"));
    }

    private String extractStatus(JsonNode root) {
        return firstText(
                root.path("status"),
                root.path("state"),
                root.path("task_status"),
                root.path("data").path("status"),
                root.path("data").path("state"),
                root.path("data").path("task_status"),
                root.path("output").path("status"),
                root.path("output").path("state"),
                root.path("output").path("task_status"));
    }

    private String extractErrorMessage(JsonNode root) {
        String error = firstText(
                root.path("message"),
                root.path("msg"),
                root.path("error"),
                root.path("error").path("message"),
                root.path("data").path("message"),
                root.path("output").path("message"));
        return hasText(error) ? error : "视频生成任务失败";
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

    private String normalizeStatus(String status) {
        return hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "";
    }

    private boolean isSuccessStatus(String status) {
        return "success".equals(status)
                || "succeeded".equals(status)
                || "completed".equals(status)
                || "done".equals(status)
                || "finished".equals(status);
    }

    private boolean isFailureStatus(String status) {
        return "failed".equals(status)
                || "error".equals(status)
                || "cancelled".equals(status)
                || "canceled".equals(status)
                || "rejected".equals(status);
    }

    private boolean isPendingStatus(String status) {
        return "pending".equals(status)
                || "queued".equals(status)
                || "running".equals(status)
                || "processing".equals(status)
                || "in_progress".equals(status)
                || "submitted".equals(status);
    }

    private int resolveDuration(Storyboard shot) {
        return shot.getDuration() != null && shot.getDuration() > 0 ? shot.getDuration() : 5;
    }

    private VideoGenerationProfile resolveVideoGenerationProfile(Storyboard shot, int fallbackDuration) {
        int baseDuration = fallbackDuration > 0 ? fallbackDuration : 5;
        String tier = resolveMotionTier(shot);
        if ("A".equalsIgnoreCase(tier)) {
            return new VideoGenerationProfile(Math.min(Math.max(baseDuration, 5), 10), "pro", "1080P");
        }
        if ("B".equalsIgnoreCase(tier)) {
            return new VideoGenerationProfile(Math.min(Math.max(baseDuration, 5), 8), "standard", "720P");
        }
        return new VideoGenerationProfile(Math.min(Math.max(baseDuration, 4), 6), "standard", "720P");
    }

    private String resolveMotionTier(Storyboard shot) {
        if (shot == null || !hasText(shot.getMotionTier())) {
            return "C";
        }
        String normalized = shot.getMotionTier().trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "A", "B", "C" -> normalized;
            default -> "C";
        };
    }

    private void maybeDowngradeToTierB(Storyboard shot, String reason) {
        if (shot == null || shot.getId() == null || !"A".equalsIgnoreCase(resolveMotionTier(shot))) {
            return;
        }
        shot.setMotionTier("B");
        shot.setMotionTierReason("A档视频失败，自动降级为B档轻动态视频");
        shot.setDynamicSelected(true);
        shot.setRenderMode("video");
        shot.setVideoTaskStatus("degraded");
        String fallbackReason = hasText(reason) ? reason : "视频服务异常";
        String originalReason = hasText(shot.getDynamicReason()) ? shot.getDynamicReason() : "";
        shot.setDynamicReason((originalReason + "；A档降级：" + fallbackReason).replaceAll("^；", ""));
        storyboardMapper.updateById(shot);
    }

    private String resolvePrompt(Storyboard shot) {
        String basePrompt;
        if (hasText(shot.getVideoPrompt())) {
            basePrompt = shot.getVideoPrompt();
        } else {
            basePrompt = hasText(shot.getDescription()) ? shot.getDescription() : null;
        }
        if (!hasText(basePrompt)) {
            return null;
        }
        Project project = shot.getProjectId() != null ? projectService.getProject(shot.getProjectId()) : null;
        String visualGuide = ProjectStyleSupport.buildVisualCreationRules(
                project != null ? project.getProjectType() : null,
                project != null ? project.getGenre() : null)
                .replace("\n", " ")
                .replace("- ", " ")
                .replaceAll("\\s+", " ")
                .trim();

        // 视频生成只负责“让首帧动起来”，避免把图片提示词再次扩写成重绘画面。
        StringBuilder sb = new StringBuilder();
        sb.append("Commercial vertical short-drama I2V shot, one continuous 9:16 live-action take. ")
                .append("The first frame is fixed and must remain the identity and scene anchor: same face, hairstyle, outfit, age, body shape, props, lighting, camera angle, and room/location layout. ")
                .append("Do not redraw the image, do not turn it into sketch, comic, line art, monochrome, CGI, or posterized frames. ")
                .append("Use video motion, not image description: slow push-in or gentle pan, natural breathing, blinking, subtle head/hand reaction, hair and clothing micro motion, and environmental light/shadow movement. ")
                .append("Keep a beginning-middle-end rhythm inside the same shot, no cuts, no scene jump, no new person, no face morphing, no wardrobe change. ")
                .append("Motion directive: ").append(basePrompt);

        // 附加角色一致性要求
        if (shot.getCharacterId() != null) {
            Character character = characterMapper.selectById(shot.getCharacterId());
            if (character != null && hasText(character.getName())) {
                sb.append("。角色主体：").append(character.getName());
                if (hasText(character.getAppearance())) {
                    sb.append("（").append(trimToLength(character.getAppearance(), 60)).append("）");
                }
                sb.append("，必须保持同一张脸、同一发型、同一服装、同一年龄感，不漂移不换人");
            }
        }
        sb.append("。项目视觉约束只作为风格边界，不要覆盖首帧：").append(trimToLength(visualGuide, 180));
        sb.append("。成片目标：可直接作为短剧平台片段使用，运动连续、有真实镜头感，避免静帧幻灯片、线稿化、插画化和镜头碎片化。");
        return sb.toString();
    }

    private String trimToLength(String text, int maxLen) {
        if (text == null || text.isEmpty()) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private boolean isAliyunProvider(String provider) {
        if (!hasText(provider)) {
            return false;
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        return "aliyun".equals(normalized)
                || "dashscope".equals(normalized)
                || "wanx".equals(normalized);
    }

    private boolean isComfyUiProvider(String provider) {
        return "comfyui".equalsIgnoreCase(provider != null ? provider.trim() : "");
    }

    private VideoTaskSubmission submitComfyUiVideoTask(Long userId, AiResolvedConfig config, Storyboard shot) {
        String prompt = resolvePrompt(shot);
        if (!hasText(prompt)) {
            throw new BusinessException("动态镜头缺少视频提示词，无法发起 ComfyUI 视频生成");
        }
        VideoGenerationProfile profile = resolveVideoGenerationProfile(shot, resolveDuration(shot));
        String resolution = profile.resolution();
        String quality = profile.qualityTier();
        int duration = profile.durationSeconds();
        List<String> referenceImageUrls = resolveReferenceImageUrls(shot);
        String referenceImageUrl = referenceImageUrls.isEmpty() ? null : referenceImageUrls.get(0);

        log.debug("Start ComfyUI video generation: shotId={}, shotNo={}, hasImage={}, refCount={}, duration={}, resolution={}",
                shot.getId(), shot.getShotNo(), hasText(referenceImageUrl), referenceImageUrls.size(), duration, resolution);

        try {
            VideoAiProvider provider = aiProviderFactory.getVideoProvider(userId);
            String videoUrl;
            if (hasText(referenceImageUrl)) {
                videoUrl = provider.generateVideoFromImage(referenceImageUrl, referenceImageUrls, prompt, duration, resolution, quality, false);
            } else {
                videoUrl = provider.generateVideoFromText(prompt, duration, resolution, quality, false);
            }
            if (hasText(videoUrl)) {
                videoUrl = persistVideoUrl(videoUrl);
            }
            log.debug("ComfyUI video generation completed: shotId={}, shotNo={}, videoUrl={}",
                    shot.getId(), shot.getShotNo(), videoUrl);
            return new VideoTaskSubmission("comfyui", null, null, videoUrl);
        } catch (Exception e) {
            log.error("ComfyUI video generation failed for shot {}", shot.getShotNo(), e);
            maybeDowngradeToTierB(shot, e.getMessage());
            throw new RuntimeException("视频生成失败: " + e.getMessage(), e);
        }
    }

    private String resolveReferenceImageUrl(Storyboard shot) {
        List<String> referenceImages = resolveReferenceImageUrls(shot);
        return referenceImages.isEmpty() ? null : referenceImages.get(0);
    }

    private List<String> resolveReferenceImageUrls(Storyboard shot) {
        if (shot == null) {
            return List.of();
        }
        Set<String> references = new LinkedHashSet<>();
        addPublicReference(references, shot.getImageUrl());

        if (shot.getCharacterId() != null) {
            Character character = characterMapper.selectById(shot.getCharacterId());
            if (character != null) {
                addPublicReference(references, character.getImageUrl());
                // 同时加入该角色全部多角度参考图（通常 3 张：正面/侧面/回眸）
                addCharacterMultiAngleReferences(references, character);
            }
        }
        if (shot.getSceneId() != null) {
            Scene scene = sceneMapper.selectById(shot.getSceneId());
            if (scene != null) {
                addPublicReference(references, scene.getImageUrl());
            }
        }
        addPublicReference(references, findPreviousCharacterReference(shot));
        addPublicReference(references, findPreviousProjectReference(shot));
        return new ArrayList<>(references);
    }

    private void addPublicReference(Set<String> references, String imageUrl) {
        if (!hasText(imageUrl)) {
            return;
        }
        String publicUrl = publicAssetStorageService.ensurePublicUrl(imageUrl, "reference-images", "png");
        if (hasText(publicUrl)) {
            references.add(publicUrl);
        }
    }

    /**
     * 解析角色的多角度参考图 JSON 数组并加入参考列表。
     * {@link Character#getImageUrls()} 存储格式为 {@code ["url1","url2","url3"]}，
     * 对应正面、四分之三侧面、回眸侧脸三张。
     */
    private void addCharacterMultiAngleReferences(Set<String> references, Character character) {
        String imageUrlsJson = character.getImageUrls();
        if (!hasText(imageUrlsJson)) {
            return;
        }
        try {
            JsonNode arr = objectMapper.readTree(imageUrlsJson);
            if (arr.isArray()) {
                for (JsonNode urlNode : arr) {
                    addPublicReference(references, urlNode.asText(null));
                }
            }
        } catch (Exception ignored) {
            // JSON 解析失败，忽略
        }
    }

    private String findPreviousCharacterReference(Storyboard shot) {
        if (shot.getProjectId() == null || shot.getShotNo() == null || shot.getCharacterId() == null) {
            return null;
        }
        Storyboard previousWithSameCharacter = storyboardMapper.selectOne(
                new LambdaQueryWrapper<Storyboard>()
                        .eq(Storyboard::getProjectId, shot.getProjectId())
                        .eq(Storyboard::getCharacterId, shot.getCharacterId())
                        .lt(Storyboard::getShotNo, shot.getShotNo())
                        .isNotNull(Storyboard::getImageUrl)
                        .orderByDesc(Storyboard::getShotNo)
                        .last("LIMIT 1"));
        return previousWithSameCharacter != null ? previousWithSameCharacter.getImageUrl() : null;
    }

    private String findPreviousProjectReference(Storyboard shot) {
        if (shot.getProjectId() == null || shot.getShotNo() == null) {
            return null;
        }
        Storyboard previousAnyShot = storyboardMapper.selectOne(
                new LambdaQueryWrapper<Storyboard>()
                        .eq(Storyboard::getProjectId, shot.getProjectId())
                        .lt(Storyboard::getShotNo, shot.getShotNo())
                        .isNotNull(Storyboard::getImageUrl)
                        .orderByDesc(Storyboard::getShotNo)
                        .last("LIMIT 1"));
        return previousAnyShot != null ? previousAnyShot.getImageUrl() : null;
    }

    private String resolveAliyunVideoModel(String configuredModel, String referenceImageUrl) {
        if (hasText(referenceImageUrl)) {
            if (!hasText(configuredModel) || "wan2.6-t2v".equalsIgnoreCase(configuredModel.trim())) {
                return "wan2.7-i2v";
            }
        }
        return hasText(configuredModel) ? configuredModel : "wan2.7-i2v";
    }

    private String resolveAliyunVideoEndpoint(String baseUrl) {
        return normalizeAliyunApiBase(baseUrl) + "/services/aigc/video-generation/video-synthesis";
    }

    private boolean requiresInputMediaField(String model) {
        if (!hasText(model)) {
            return false;
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("wan2.7-i2v");
    }

    private String normalizeAliyunApiBase(String value) {
        String normalized = hasText(value) ? trimTrailingSlash(value.trim()) : "https://dashscope.aliyuncs.com/api/v1";
        String[] suffixes = new String[] {
                "/services/aigc/video-generation/video-synthesis",
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

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String persistVideoUrl(String videoUrl) {
        return publicAssetStorageService.ensurePublicUrl(videoUrl, "videos", "mp4");
    }

    private record VideoGenerationProfile(int durationSeconds, String qualityTier, String resolution) {}
}
