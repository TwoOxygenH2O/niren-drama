package com.niren.drama.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.AiResolvedConfig;
import com.niren.drama.ai.trace.AiTraceSupport;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiVideoGenerationService {

    private static final int MAX_POLL_ATTEMPTS = 60;
    private static final long POLL_INTERVAL_MS = 3000L;

    private final AiProviderFactory aiProviderFactory;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

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
        return generateCustomVideo(config, shot);
    }

    private String generateAliyunVideo(AiResolvedConfig config, Storyboard shot) {
        if (!hasText(config.apiKey())) {
            throw new BusinessException("未配置视频生成 API Key，请先在 AI 配置中设置视频服务");
        }
        String prompt = resolvePrompt(shot);
        if (!hasText(prompt)) {
            throw new BusinessException("动态镜头缺少视频提示词，无法发起阿里云视频接口");
        }

        String endpoint = resolveAliyunVideoEndpoint(config.baseUrl());
        log.debug("Start aliyun video generation: shotId={}, shotNo={}, endpoint={}, model={}, promptLength={}",
            shot.getId(), shot.getShotNo(), endpoint, config.model(), prompt.length());
        String requestBody = null;
        HttpResponse<String> response = null;
        String responseBody = null;
        String videoUrl = null;
        String error = null;
        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "Authorization", AiTraceSupport.maskBearer(config.apiKey()),
                "X-DashScope-Async", "enable");

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", config.model());

            ObjectNode input = body.putObject("input");
            input.put("prompt", prompt);

            ObjectNode parameters = body.putObject("parameters");
            parameters.put("resolution", "720P");
            parameters.put("ratio", "9:16");
            parameters.put("prompt_extend", true);
            parameters.put("watermark", true);
            parameters.put("duration", resolveDuration(shot));

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
                log.debug("Aliyun video generation returned direct url: shotId={}, shotNo={}, videoUrl={}",
                        shot.getId(), shot.getShotNo(), videoUrl);
                return videoUrl;
            }

            String statusUrl = resolveAliyunStatusUrl(endpoint, root);
            if (!hasText(statusUrl)) {
                String taskId = extractTaskId(root);
                if (hasText(taskId)) {
                    statusUrl = normalizeAliyunApiBase(config.baseUrl()) + "/tasks/" + taskId;
                }
            }

            log.debug("Aliyun video generation switched to polling: shotId={}, shotNo={}, statusUrl={}",
                    shot.getId(), shot.getShotNo(), statusUrl);

            if (!hasText(statusUrl)) {
                error = "视频接口未返回可用视频地址，也未提供任务查询地址: " + responseBody;
                throw new RuntimeException(error);
            }

            videoUrl = pollVideoResult(config.provider(), config.apiKey(), statusUrl);
            log.debug("Aliyun video polling complete: shotId={}, shotNo={}, videoUrl={}",
                    shot.getId(), shot.getShotNo(), videoUrl);
            return videoUrl;
        } catch (Exception e) {
            if (!hasText(error)) {
                error = e.getMessage();
            }
            log.error("Video generation failed for shot {}", shot.getShotNo(), e);
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
                    hasText(videoUrl),
                    videoUrl,
                    error);
        }
    }

    private String generateCustomVideo(AiResolvedConfig config, Storyboard shot) {
        if (!hasText(config.apiKey())) {
            throw new BusinessException("未配置自定义视频接口 API Key");
        }
        String prompt = resolvePrompt(shot);
        if (!hasText(prompt)) {
            throw new BusinessException("动态镜头缺少视频提示词，无法发起自定义视频接口");
        }

        String endpoint = resolveCustomVideoEndpoint(config.baseUrl());
        log.debug("Start custom video generation: shotId={}, shotNo={}, endpoint={}, model={}, promptLength={}, hasImage={}",
            shot.getId(), shot.getShotNo(), endpoint, config.model(), prompt.length(), hasText(shot.getImageUrl()));
        String requestBody = null;
        HttpResponse<String> response = null;
        String responseBody = null;
        String videoUrl = null;
        String error = null;
        Map<String, String> headers = AiTraceSupport.jsonHeaders(config.apiKey());

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", config.model());
            body.put("prompt", prompt);
            if (hasText(shot.getImageUrl())) {
                body.put("image_url", shot.getImageUrl());
            }
            body.put("duration", resolveDuration(shot));
            body.put("size", "1080x1920");
            body.put("quality", "standard");
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
                log.debug("Custom video generation returned direct url: shotId={}, shotNo={}, videoUrl={}",
                        shot.getId(), shot.getShotNo(), videoUrl);
                return videoUrl;
            }

            String statusUrl = resolveGenericStatusUrl(endpoint, root);
            if (!hasText(statusUrl)) {
                String taskId = extractTaskId(root);
                if (hasText(taskId)) {
                    statusUrl = resolveGenericTaskEndpoint(endpoint, taskId);
                }
            }

            log.debug("Custom video generation switched to polling: shotId={}, shotNo={}, statusUrl={}",
                    shot.getId(), shot.getShotNo(), statusUrl);

            if (!hasText(statusUrl)) {
                error = "视频接口未返回可用视频地址，也未提供任务查询地址: " + responseBody;
                throw new RuntimeException(error);
            }

            videoUrl = pollVideoResult(config.provider(), config.apiKey(), statusUrl);
            log.debug("Custom video polling complete: shotId={}, shotNo={}, videoUrl={}",
                    shot.getId(), shot.getShotNo(), videoUrl);
            return videoUrl;
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
                    hasText(videoUrl),
                    videoUrl,
                    error);
        }
    }

    private String pollVideoResult(String provider, String apiKey, String statusUrl) throws Exception {
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Thread.sleep(POLL_INTERVAL_MS);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();
            String error = response.statusCode() >= 400 ? ("HTTP " + response.statusCode() + " - " + responseBody) : null;
            JsonNode root = objectMapper.readTree(responseBody);
            String videoUrl = extractVideoUrl(root);
            String status = normalizeStatus(extractStatus(root));
                log.debug("Video polling attempt: provider={}, statusUrl={}, attempt={}, status={}, hasVideoUrl={}",
                    provider, statusUrl, attempt + 1, status, hasText(videoUrl));

            AiTraceSupport.record(
                    "video",
                    provider,
                    "poll_video_task",
                    "GET",
                    statusUrl,
                    Map.of("Authorization", AiTraceSupport.maskBearer(apiKey)),
                    null,
                    response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(null),
                    responseBody,
                    responseBody != null ? responseBody.length() : null,
                    response.statusCode() < 400 && (hasText(videoUrl) || isPendingStatus(status) || isSuccessStatus(status)),
                    videoUrl,
                    error);

            if (response.statusCode() >= 400) {
                throw new RuntimeException(error);
            }
            if (hasText(videoUrl) && (isSuccessStatus(status) || !hasText(status))) {
                return videoUrl;
            }
            if (isFailureStatus(status)) {
                throw new RuntimeException(extractErrorMessage(root));
            }
            if (isSuccessStatus(status) && !hasText(videoUrl)) {
                throw new RuntimeException("视频任务已成功，但响应中没有可用视频地址: " + responseBody);
            }
        }
        throw new RuntimeException("视频生成任务轮询超时，请稍后重试");
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

    private String resolvePrompt(Storyboard shot) {
        if (hasText(shot.getVideoPrompt())) {
            return shot.getVideoPrompt();
        }
        return hasText(shot.getDescription()) ? shot.getDescription() : null;
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

    private String resolveAliyunVideoEndpoint(String baseUrl) {
        return normalizeAliyunApiBase(baseUrl) + "/services/aigc/video-generation/video-synthesis";
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
}