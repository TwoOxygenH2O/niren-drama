package com.niren.drama.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.AiResolvedConfig;
import com.niren.drama.entity.Storyboard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisualReviewService {

    private static final int DEFAULT_MAX_KEYFRAMES = 3;

    private final ObjectMapper objectMapper;
    private final AiProviderFactory aiProviderFactory;

    @Value("${niren.visual-review.enabled:true}")
    private boolean enabled = true;

    @Value("${niren.visual-review.base-url:}")
    private String configuredBaseUrl;

    @Value("${niren.visual-review.api-key:}")
    private String configuredApiKey;

    @Value("${niren.visual-review.model:}")
    private String configuredModel;

    @Value("${niren.visual-review.max-keyframes:3}")
    private int maxKeyframes = DEFAULT_MAX_KEYFRAMES;

    @Value("${niren.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();

    public VisualReviewReport review(Long userId,
                                     Storyboard shot,
                                     Path videoPath,
                                     Path referenceImagePath,
                                     int expectedDurationSeconds,
                                     Map<String, Object> localMetrics) {
        if (!enabled) {
            return VisualReviewReport.skipped("disabled");
        }
        if (shot == null || videoPath == null || !Files.exists(videoPath)) {
            return VisualReviewReport.skipped("missing_video");
        }
        ReviewConfig config = resolveReviewConfig(userId);
        if (config == null || !config.isUsable()) {
            return VisualReviewReport.skipped("missing_vlm_config");
        }
        if (!hasText(ffmpegPath)) {
            return VisualReviewReport.skipped("missing_ffmpeg");
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("niren-vlm-review-");
            List<ImagePayload> images = new ArrayList<>();
            Path normalizedReference = prepareReferenceImage(referenceImagePath, tempDir);
            if (normalizedReference != null) {
                images.add(toImagePayload("reference_first_frame", normalizedReference));
            }
            List<Path> keyframes = extractKeyframes(videoPath, tempDir, expectedDurationSeconds);
            for (int i = 0; i < keyframes.size(); i++) {
                images.add(toImagePayload("video_keyframe_" + (i + 1), keyframes.get(i)));
            }
            if (images.stream().noneMatch(image -> image.label().startsWith("video_keyframe"))) {
                return VisualReviewReport.skipped("no_keyframes");
            }

            String response = callVlm(config, shot, images, localMetrics);
            return parseReviewResponse(config, shot, images, response);
        } catch (Exception e) {
            log.debug("VLM visual review skipped: shotId={}, error={}", shot.getId(), e.getMessage());
            return VisualReviewReport.skipped(e.getClass().getSimpleName());
        } finally {
            deleteQuietly(tempDir);
        }
    }

    private ReviewConfig resolveReviewConfig(Long userId) {
        try {
            AiResolvedConfig textConfig = aiProviderFactory == null ? null : aiProviderFactory.resolveConfig(userId, "text");
            JsonNode extra = parseJson(textConfig == null ? null : textConfig.extra());
            boolean extraEnabled = !extra.path("visualReviewEnabled").isBoolean() || extra.path("visualReviewEnabled").asBoolean();
            if (!extraEnabled) {
                return null;
            }
            String baseUrl = firstNonBlank(
                    configuredBaseUrl,
                    text(extra, "visualReviewBaseUrl", "vlmBaseUrl"),
                    textConfig == null ? null : textConfig.baseUrl());
            String apiKey = firstNonBlank(
                    configuredApiKey,
                    text(extra, "visualReviewApiKey", "vlmApiKey"),
                    textConfig == null ? null : textConfig.apiKey());
            String model = firstNonBlank(
                    configuredModel,
                    text(extra, "visualReviewModel", "vlmModel", "visionModel"),
                    textConfig == null ? null : textConfig.model());
            boolean explicitVisualConfig = hasText(configuredBaseUrl)
                    || hasText(configuredApiKey)
                    || hasText(configuredModel)
                    || hasText(text(extra, "visualReviewBaseUrl", "vlmBaseUrl"))
                    || hasText(text(extra, "visualReviewApiKey", "vlmApiKey"))
                    || hasText(text(extra, "visualReviewModel", "vlmModel", "visionModel"));
            if (!explicitVisualConfig && !isLikelyVisionModel(model)) {
                return null;
            }
            return new ReviewConfig(baseUrl, apiKey, model);
        } catch (Exception e) {
            log.debug("Unable to resolve VLM visual review config: {}", e.getMessage());
            return null;
        }
    }

    private Path prepareReferenceImage(Path referenceImagePath, Path tempDir) {
        if (referenceImagePath == null || !Files.exists(referenceImagePath)) {
            return null;
        }
        Path output = tempDir.resolve("reference.jpg");
        List<String> command = List.of(
                ffmpegPath,
                "-hide_banner",
                "-y",
                "-i", referenceImagePath.toString(),
                "-vf", "scale=512:-1",
                "-frames:v", "1",
                output.toString()
        );
        try {
            runProcess(command, Duration.ofSeconds(16));
            return Files.exists(output) ? output : referenceImagePath;
        } catch (Exception ignored) {
            return referenceImagePath;
        }
    }

    private List<Path> extractKeyframes(Path videoPath, Path tempDir, int expectedDurationSeconds) throws Exception {
        int frameCount = Math.max(1, Math.min(maxKeyframes <= 0 ? DEFAULT_MAX_KEYFRAMES : maxKeyframes, 5));
        int duration = expectedDurationSeconds <= 0 ? 6 : expectedDurationSeconds;
        double fps = Math.min(1.25d, Math.max(0.25d, frameCount / (double) Math.max(1, duration)));
        String pattern = tempDir.resolve("keyframe_%02d.jpg").toString();
        List<String> command = List.of(
                ffmpegPath,
                "-hide_banner",
                "-y",
                "-i", videoPath.toString(),
                "-vf", "fps=" + String.format(Locale.ROOT, "%.3f", fps) + ",scale=512:-1",
                "-frames:v", String.valueOf(frameCount),
                pattern
        );
        runProcess(command, Duration.ofSeconds(30));
        try (var stream = Files.list(tempDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().startsWith("keyframe_"))
                    .filter(path -> path.getFileName().toString().endsWith(".jpg"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private ImagePayload toImagePayload(String label, Path path) throws Exception {
        String mime = mimeType(path);
        String data = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
        return new ImagePayload(label, "data:" + mime + ";base64," + data);
    }

    private String callVlm(ReviewConfig config,
                           Storyboard shot,
                           List<ImagePayload> images,
                           Map<String, Object> localMetrics) throws Exception {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", buildReviewPrompt(shot, localMetrics)));
        for (ImagePayload image : images) {
            content.add(Map.of("type", "text", "text", "Image label: " + image.label()));
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", image.dataUrl())
            ));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("temperature", 0.1d);
        body.put("max_tokens", 1000);
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是 CASR 视觉审片器 2.0，只输出严格 JSON，不输出 Markdown。"),
                Map.of("role", "user", "content", content)
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(chatCompletionsUrl(config.baseUrl())))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("VLM HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String buildReviewPrompt(Storyboard shot, Map<String, Object> localMetrics) {
        return """
                请逐镜审片：对比 reference_first_frame 与 video_keyframe_*，判断视频是否可作为竖屏真人短剧发布镜头。
                重点识别：
                1. 人物身份漂移：脸、年龄、性别、妆造与参考图不一致。
                2. 服装不一致：发饰、衣服颜色、朝代质感与参考图或分镜不一致。
                3. 脸部崩坏：五官变形、眼口错位、手脸粘连、不可发布。
                4. 动作不符合分镜：关键帧没有出现分镜要求的转身、奔跑、对视、拔簪等动作。
                5. 画面不可发布：严重糊化、黑屏、穿帮、怪异肢体、血腥违规、字幕遮挡主体。
                6. 动图化静帧：只是整张图推拉/漂移/轻微眨眼，没有人物局部表演、衣袂/头发/环境视差。

                分镜信息：
                shotId: %s
                episodeNo: %s
                shotNo: %s
                durationSeconds: %s
                description: %s
                cameraAngle: %s
                dialogue: %s
                narration: %s
                imagePrompt: %s
                videoPrompt: %s
                motionLevel: %s
                localMetrics: %s

                只输出 JSON：
                {
                  "publishable": true,
                  "summary": "一句话审片结论",
                  "scores": {
                    "identityConsistency": 0,
                    "wardrobeConsistency": 0,
                    "faceQuality": 0,
                    "motionPerformance": 0,
                    "storyboardMatch": 0,
                    "publishability": 0
                  },
                  "issues": [
                    {
                      "type": "identity_drift|wardrobe_inconsistent|face_broken|action_mismatch|animated_still|unpublishable_frame|storyboard_mismatch|reference_mismatch",
                      "severity": "blocking|warning|info",
                      "title": "短标题",
                      "evidence": "引用关键帧证据，说明为什么失败",
                      "recommendedAction": "retryVideo|switchWan|switchHunyuan|regenerateFirstFrame"
                    }
                  ]
                }
                没有问题时 issues 输出空数组。
                """.formatted(
                shot.getId(),
                shot.getEpisodeNo(),
                shot.getShotNo(),
                shot.getDuration(),
                safeText(shot.getDescription()),
                safeText(shot.getCameraAngle()),
                safeText(shot.getDialogue()),
                safeText(shot.getNarration()),
                safeText(shot.getImagePrompt()),
                safeText(shot.getVideoPrompt()),
                safeText(shot.getMotionLevel()),
                localMetrics == null ? Map.of() : localMetrics
        );
    }

    private boolean isLikelyVisionModel(String model) {
        if (!hasText(model)) {
            return false;
        }
        String normalized = model.toLowerCase(Locale.ROOT);
        return normalized.contains("vision")
                || normalized.contains("vl")
                || normalized.contains("gpt-4o")
                || normalized.contains("gpt-4.1")
                || normalized.contains("gemini")
                || normalized.contains("omni")
                || normalized.contains("pixtral")
                || normalized.contains("llava")
                || normalized.contains("qwen2.5-vl")
                || normalized.contains("qwen-vl")
                || normalized.contains("doubao");
    }

    private VisualReviewReport parseReviewResponse(ReviewConfig config,
                                                   Storyboard shot,
                                                   List<ImagePayload> images,
                                                   String responseBody) throws Exception {
        JsonNode response = objectMapper.readTree(responseBody);
        String content = extractAssistantContent(response);
        JsonNode root = objectMapper.readTree(extractJsonObject(content));
        JsonNode scores = root.path("scores");

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("visualAnalyzer", "vlm_keyframe_review");
        metrics.put("model", config.model());
        metrics.put("imageCount", images.size());
        metrics.put("publishable", root.path("publishable").asBoolean(true));
        metrics.put("summary", root.path("summary").asText(""));
        Map<String, Object> vlmScores = new LinkedHashMap<>();
        for (String name : List.of("identityConsistency", "wardrobeConsistency", "faceQuality",
                "motionPerformance", "storyboardMatch", "publishability")) {
            if (scores.has(name)) {
                vlmScores.put(name, scores.path(name).asInt());
            }
        }
        metrics.put("vlmScores", vlmScores);

        List<VisualReviewFinding> findings = new ArrayList<>();
        for (JsonNode issue : root.path("issues")) {
            VisualReviewFinding finding = toFinding(issue, metrics);
            if (finding != null) {
                findings.add(finding);
            }
        }
        addScoreFindings(findings, vlmScores, metrics);
        if (!root.path("publishable").asBoolean(true) && findings.isEmpty()) {
            findings.add(new VisualReviewFinding(
                    "unpublishable_frame",
                    "blocking",
                    "画面不可发布",
                    firstNonBlank(root.path("summary").asText(null), "VLM 审片判定该镜头不可发布。"),
                    "retryVideo",
                    metrics
            ));
        }
        return new VisualReviewReport(true, null, metrics, findings);
    }

    private VisualReviewFinding toFinding(JsonNode issue, Map<String, Object> metrics) {
        if (issue == null || issue.isMissingNode() || issue.isNull()) {
            return null;
        }
        String issueType = canonicalIssueType(issue.path("type").asText(""));
        if (!hasText(issueType)) {
            return null;
        }
        String severity = canonicalSeverity(issue.path("severity").asText(""));
        String action = canonicalAction(issue.path("recommendedAction").asText(""), issueType);
        String title = firstNonBlank(issue.path("title").asText(null), defaultTitle(issueType));
        String evidence = firstNonBlank(issue.path("evidence").asText(null), issue.path("message").asText(null));
        String message = firstNonBlank(evidence, title);
        return new VisualReviewFinding(issueType, severity, title, message, action, metrics);
    }

    private void addScoreFindings(List<VisualReviewFinding> findings,
                                  Map<String, Object> scores,
                                  Map<String, Object> metrics) {
        addScoreFinding(findings, scores, metrics, "identityConsistency", "identity_drift", 60, 76);
        addScoreFinding(findings, scores, metrics, "wardrobeConsistency", "wardrobe_inconsistent", 60, 76);
        addScoreFinding(findings, scores, metrics, "faceQuality", "face_broken", 58, 76);
        addScoreFinding(findings, scores, metrics, "motionPerformance", "animated_still", 58, 76);
        addScoreFinding(findings, scores, metrics, "storyboardMatch", "action_mismatch", 60, 78);
        addScoreFinding(findings, scores, metrics, "publishability", "unpublishable_frame", 58, 76);
    }

    private void addScoreFinding(List<VisualReviewFinding> findings,
                                 Map<String, Object> scores,
                                 Map<String, Object> metrics,
                                 String scoreName,
                                 String issueType,
                                 int blockingThreshold,
                                 int warningThreshold) {
        if (findings.stream().anyMatch(finding -> issueType.equals(finding.issueType()))) {
            return;
        }
        Object raw = scores.get(scoreName);
        if (!(raw instanceof Number number)) {
            return;
        }
        int score = number.intValue();
        if (score >= warningThreshold) {
            return;
        }
        String severity = score < blockingThreshold ? "blocking" : "warning";
        findings.add(new VisualReviewFinding(
                issueType,
                severity,
                defaultTitle(issueType),
                "VLM 审片评分 " + scoreName + "=" + score + "，低于可发布阈值。",
                canonicalAction("", issueType),
                metrics
        ));
    }

    private String extractAssistantContent(JsonNode response) {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : content) {
                if (item.path("type").asText("").equals("text")) {
                    builder.append(item.path("text").asText());
                }
            }
            return builder.toString();
        }
        return "";
    }

    private String extractJsonObject(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("missing_json_object");
        }
        return text.substring(start, end + 1);
    }

    private String canonicalIssueType(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "identity_drift", "person_identity_drift", "character_drift", "face_identity_mismatch" -> "identity_drift";
            case "wardrobe_inconsistent", "costume_inconsistent", "clothing_mismatch" -> "wardrobe_inconsistent";
            case "face_broken", "face_deformed", "bad_face", "facial_artifact" -> "face_broken";
            case "action_mismatch", "motion_mismatch", "performance_mismatch" -> "action_mismatch";
            case "animated_still", "gif_like", "static_pan", "weak_performance" -> "animated_still";
            case "unpublishable_frame", "not_publishable", "severe_artifact" -> "unpublishable_frame";
            case "storyboard_mismatch", "prompt_mismatch" -> "storyboard_mismatch";
            case "reference_mismatch", "first_frame_mismatch" -> "reference_mismatch";
            default -> "";
        };
    }

    private String canonicalSeverity(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "blocking", "blocker", "fatal" -> "blocking";
            case "info", "minor" -> "info";
            default -> "warning";
        };
    }

    private String canonicalAction(String raw, String issueType) {
        String value = raw == null ? "" : raw.trim();
        if ("retryVideo".equals(value) || "switchWan".equals(value) || "switchHunyuan".equals(value)
                || "regenerateFirstFrame".equals(value)) {
            return value;
        }
        return switch (issueType) {
            case "animated_still" -> "switchWan";
            case "action_mismatch", "storyboard_mismatch" -> "retryVideo";
            case "reference_mismatch" -> "regenerateFirstFrame";
            default -> "retryVideo";
        };
    }

    private String defaultTitle(String issueType) {
        return switch (issueType) {
            case "identity_drift" -> "人物身份漂移";
            case "wardrobe_inconsistent" -> "服装不一致";
            case "face_broken" -> "脸部崩坏";
            case "action_mismatch" -> "动作不符合分镜";
            case "animated_still" -> "疑似动图化静帧";
            case "unpublishable_frame" -> "画面不可发布";
            case "storyboard_mismatch" -> "画面偏离分镜";
            case "reference_mismatch" -> "视频偏离参考图";
            default -> "VLM 视觉审片异常";
        };
    }

    private JsonNode parseJson(String json) {
        if (!hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode node, String... names) {
        if (node == null) {
            return "";
        }
        for (String name : names) {
            String value = node.path(name).asText("");
            if (hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String chatCompletionsUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed;
        }
        return trimmed.replaceAll("/+$", "") + "/chat/completions";
    }

    private String mimeType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private String runProcess(List<String> args, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("process_timeout");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("process_failed: " + output);
        }
        return output.toString();
    }

    private void deleteQuietly(Path tempDir) {
        if (tempDir == null) {
            return;
        }
        try (var stream = Files.walk(tempDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best effort cleanup
                }
            });
        } catch (Exception ignored) {
            // best effort cleanup
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String safeText(String value) {
        return hasText(value) ? value : "";
    }

    private record ReviewConfig(String baseUrl, String apiKey, String model) {
        private boolean isUsable() {
            return baseUrl != null && !baseUrl.isBlank()
                    && apiKey != null && !apiKey.isBlank()
                    && model != null && !model.isBlank();
        }
    }

    private record ImagePayload(String label, String dataUrl) {
    }
}
