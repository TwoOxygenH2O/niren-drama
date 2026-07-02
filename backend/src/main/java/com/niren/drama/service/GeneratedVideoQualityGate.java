package com.niren.drama.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratedVideoQualityGate {

    private final VisualQualityAnalyzer visualQualityAnalyzer;

    @Value("${niren.upload.path:./uploads}")
    private String uploadPath;

    @Value("${niren.upload.base-url:http://localhost:8080/api/files}")
    private String publicBaseUrl;

    @Value("${niren.generated-video.quality-gate.enabled:true}")
    private boolean enabled = true;

    public Result evaluate(String videoUrl, Integer expectedDurationSeconds) {
        if (!enabled) {
            return Result.accepted(false, "disabled", null);
        }
        Path localVideo = resolveLocalPublicPath(videoUrl);
        if (localVideo == null) {
            return Result.accepted(false, "non_local_video", null);
        }
        if (!Files.exists(localVideo)) {
            return Result.accepted(false, "missing_local_video", null);
        }
        VisualQualityReport report = visualQualityAnalyzer.analyze(localVideo, expectedDurationSeconds);
        if (report == null || !report.analyzed()) {
            String reason = report != null && report.metrics() != null
                    ? String.valueOf(report.metrics().getOrDefault("reason", "analysis_skipped"))
                    : "analysis_skipped";
            return Result.accepted(false, reason, report);
        }

        List<VisualQualityFinding> blockingFindings = report.findings() == null
                ? List.of()
                : report.findings().stream()
                .filter(finding -> "blocking".equalsIgnoreCase(finding.severity()))
                .toList();
        if (blockingFindings.isEmpty()) {
            return Result.accepted(true, "accepted", report);
        }
        String issueSummary = blockingFindings.stream()
                .map(finding -> finding.issueType() + ":" + finding.title())
                .toList()
                .toString();
        return new Result(true, false, "视频视觉质检未通过: " + issueSummary, report);
    }

    private Path resolveLocalPublicPath(String videoUrl) {
        if (!hasText(videoUrl) || !hasText(publicBaseUrl) || !hasText(uploadPath)) {
            return null;
        }
        String normalizedBase = trimTrailingSlash(publicBaseUrl.trim()) + "/";
        if (!videoUrl.startsWith(normalizedBase)) {
            return null;
        }
        String relative = URLDecoder.decode(videoUrl.substring(normalizedBase.length()), StandardCharsets.UTF_8);
        Path root = Paths.get(uploadPath).toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            log.warn("拒绝解析越界公开视频路径: url={}", videoUrl);
            return null;
        }
        return resolved;
    }

    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public record Result(boolean checked,
                         boolean acceptable,
                         String reason,
                         VisualQualityReport report) {
        static Result accepted(boolean checked, String reason, VisualQualityReport report) {
            return new Result(checked, true, hasText(reason) ? reason : "accepted", report);
        }

        private static boolean hasText(String value) {
            return value != null && !value.trim().isEmpty();
        }
    }
}
