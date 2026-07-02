package com.niren.drama.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VisualQualityAnalyzer {

    private static final int MAX_SAMPLE_FRAMES = 24;
    private static final double LOW_SHARPNESS_THRESHOLD = 0.018d;
    private static final double LOW_CONTRAST_THRESHOLD = 0.045d;
    private static final double LOW_COLORFULNESS_THRESHOLD = 0.018d;
    private static final double WASHED_GRAY_LOW_COLOR_RATIO = 0.75d;
    private static final double WEAK_MOTION_THRESHOLD = 0.004d;
    private static final double SMEAR_DIFF_THRESHOLD = 0.030d;
    private static final double GRID_ACTIVE_DIFF_THRESHOLD = 0.006d;
    private static final double ANIMATED_STILL_MIN_DIFF = 0.006d;
    private static final double ANIMATED_STILL_MAX_DIFF = 0.080d;
    private static final double GLOBAL_MOTION_RATIO_THRESHOLD = 0.50d;
    private static final double GRID_MOTION_CV_THRESHOLD = 0.62d;
    private static final double LOCAL_PERFORMANCE_CV_THRESHOLD = 0.64d;
    private static final double LOCAL_PERFORMANCE_CENTER_RATIO_THRESHOLD = 1.35d;
    private static final double LOCAL_PERFORMANCE_MIN_DIFF = 0.035d;
    private static final double STRONG_SUBJECT_CENTER_RATIO_THRESHOLD = 1.75d;
    private static final double STRONG_SUBJECT_MIN_DIFF = 0.025d;
    private static final double STRONG_ACTION_MIN_DIFF = 0.055d;
    private static final double TRANSLATION_RESIDUAL_ACTION_THRESHOLD = 0.68d;
    private static final double DUPLICATE_FRAME_DIFF_THRESHOLD = 0.0025d;
    private static final double LOW_EFFECTIVE_FPS_DUPLICATE_RATIO = 0.35d;

    @Value("${niren.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${niren.visual-quality.enabled:true}")
    private boolean enabled = true;

    public VisualQualityReport analyze(Path videoPath, Integer expectedDurationSeconds) {
        if (!enabled) {
            return VisualQualityReport.skipped("disabled");
        }
        if (videoPath == null || !Files.exists(videoPath)) {
            return VisualQualityReport.skipped("missing_video");
        }
        if (!hasText(ffmpegPath)) {
            return VisualQualityReport.skipped("missing_ffmpeg");
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("niren-visual-quality-");
            extractFrames(videoPath, tempDir, expectedDurationSeconds);
            List<BufferedImage> frames = readFrames(tempDir);
            return analyzeFrames(frames);
        } catch (Exception e) {
            log.debug("Visual quality analysis skipped: path={}, error={}", videoPath, e.getMessage());
            return VisualQualityReport.skipped(e.getClass().getSimpleName());
        } finally {
            deleteQuietly(tempDir);
        }
    }

    public VisualQualityReport analyzeFrames(List<BufferedImage> frames) {
        List<BufferedImage> safeFrames = frames == null ? List.of() : frames.stream()
                .filter(frame -> frame != null && frame.getWidth() > 2 && frame.getHeight() > 2)
                .toList();
        if (safeFrames.isEmpty()) {
            return VisualQualityReport.skipped("no_frames");
        }

        List<Double> sharpnessValues = new ArrayList<>();
        List<Double> contrastValues = new ArrayList<>();
        List<Double> colorfulnessValues = new ArrayList<>();
        for (BufferedImage frame : safeFrames) {
            FrameMetrics metrics = measureFrame(frame);
            sharpnessValues.add(metrics.sharpness());
            contrastValues.add(metrics.contrast());
            colorfulnessValues.add(metrics.colorfulness());
        }
        List<Double> diffs = new ArrayList<>();
        List<MotionProfile> motionProfiles = new ArrayList<>();
        for (int i = 1; i < safeFrames.size(); i++) {
            diffs.add(frameDifference(safeFrames.get(i - 1), safeFrames.get(i)));
            motionProfiles.add(motionProfile(safeFrames.get(i - 1), safeFrames.get(i)));
        }

        double averageSharpness = average(sharpnessValues);
        double averageContrast = average(contrastValues);
        double averageColorfulness = average(colorfulnessValues);
        double averageFrameDiff = average(diffs);
        double duplicateAdjacentFrameRatio = diffs.isEmpty() ? 0d : diffs.stream()
                .filter(diff -> diff <= DUPLICATE_FRAME_DIFF_THRESHOLD)
                .count() / (double) diffs.size();
        double activeCellRatio = average(motionProfiles.stream().map(MotionProfile::activeCellRatio).toList());
        double gridMotionCv = average(motionProfiles.stream().map(MotionProfile::gridDiffCoefficientOfVariation).toList());
        double centerToBorderDiffRatio = average(motionProfiles.stream().map(MotionProfile::centerToBorderDiffRatio).toList());
        double translationResidualRatio = average(motionProfiles.stream().map(MotionProfile::translationResidualRatio).toList());
        double globalMotionLikeRatio = motionProfiles.isEmpty() ? 0d : motionProfiles.stream()
                .filter(this::isGlobalMotionLike)
                .count() / (double) motionProfiles.size();
        boolean localPerformanceEvidence = hasLocalPerformanceEvidence(
                gridMotionCv,
                centerToBorderDiffRatio,
                averageFrameDiff,
                translationResidualRatio);
        String localPerformanceEvidenceReason = resolveLocalPerformanceEvidenceReason(
                gridMotionCv,
                centerToBorderDiffRatio,
                averageFrameDiff,
                translationResidualRatio);
        double lowDetailRatio = sharpnessValues.stream()
                .filter(value -> value < LOW_SHARPNESS_THRESHOLD)
                .count() / (double) sharpnessValues.size();
        double lowColorFrameRatio = colorfulnessValues.stream()
                .filter(value -> value < LOW_COLORFULNESS_THRESHOLD)
                .count() / (double) colorfulnessValues.size();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("sampledFrames", safeFrames.size());
        metrics.put("averageSharpness", roundMetric(averageSharpness));
        metrics.put("averageContrast", roundMetric(averageContrast));
        metrics.put("averageColorfulness", roundMetric(averageColorfulness));
        metrics.put("averageFrameDiff", roundMetric(averageFrameDiff));
        metrics.put("duplicateAdjacentFrameRatio", roundMetric(duplicateAdjacentFrameRatio));
        metrics.put("lowDetailRatio", roundMetric(lowDetailRatio));
        metrics.put("lowColorFrameRatio", roundMetric(lowColorFrameRatio));
        metrics.put("activeMotionCellRatio", roundMetric(activeCellRatio));
        metrics.put("gridMotionCoefficientOfVariation", roundMetric(gridMotionCv));
        metrics.put("centerToBorderDiffRatio", roundMetric(centerToBorderDiffRatio));
        metrics.put("translationCompensatedResidualRatio", roundMetric(translationResidualRatio));
        metrics.put("globalMotionLikeRatio", roundMetric(globalMotionLikeRatio));
        metrics.put("localPerformanceEvidence", localPerformanceEvidence);
        metrics.put("localPerformanceEvidenceReason", localPerformanceEvidenceReason);

        List<VisualQualityFinding> findings = new ArrayList<>();
        if (lowDetailRatio >= 0.55d || averageSharpness < LOW_SHARPNESS_THRESHOLD) {
            findings.add(finding(
                    "low_visual_detail",
                    "warning",
                    "画面细节不足",
                    "抽帧检测发现画面边缘和纹理信息过低，可能呈现糊化、雾化或主体不可读。",
                    "retryVideo",
                    metrics));
        }
        if (averageContrast < LOW_CONTRAST_THRESHOLD && averageSharpness < LOW_SHARPNESS_THRESHOLD) {
            findings.add(finding(
                    "unwatchable_visual",
                    "blocking",
                    "视觉质量不可用",
                    "抽帧检测显示镜头缺少可辨认的主体和纹理，不能作为可发布短剧镜头。",
                    "retryVideo",
                    metrics));
        }
        if (safeFrames.size() >= 4
                && lowColorFrameRatio >= WASHED_GRAY_LOW_COLOR_RATIO
                && averageColorfulness < LOW_COLORFULNESS_THRESHOLD) {
            findings.add(finding(
                    "washed_gray_video",
                    "blocking",
                    "灰色视频不可用",
                    "抽帧检测发现镜头长期缺少有效色彩，疑似灰屏、雾化噪声或模型塌陷输出。",
                    "retryVideo",
                    metrics));
        }
        if (safeFrames.size() >= 4 && averageFrameDiff < WEAK_MOTION_THRESHOLD) {
            findings.add(finding(
                    "weak_motion",
                    "warning",
                    "镜头动态过弱",
                    "连续抽帧变化过低，视频可能只是轻微抖动或接近静帧。",
                    "retryVideo",
                    metrics));
        }
        if (safeFrames.size() >= 8
                && duplicateAdjacentFrameRatio >= LOW_EFFECTIVE_FPS_DUPLICATE_RATIO
                && averageSharpness >= LOW_SHARPNESS_THRESHOLD
                && averageContrast >= LOW_CONTRAST_THRESHOLD) {
            findings.add(finding(
                    "low_effective_fps",
                    "blocking",
                    "有效帧率过低",
                    "高频抽帧发现大量相邻帧重复，视频虽然可能封装为高帧率，但实际运动帧不足，播放观感会明显卡顿。",
                    "retryVideo",
                    metrics));
        }
        if (safeFrames.size() >= 5
                && averageFrameDiff >= ANIMATED_STILL_MIN_DIFF
                && averageFrameDiff <= ANIMATED_STILL_MAX_DIFF
                && globalMotionLikeRatio >= GLOBAL_MOTION_RATIO_THRESHOLD
                && activeCellRatio >= 0.45d
                && !localPerformanceEvidence
                && averageSharpness >= LOW_SHARPNESS_THRESHOLD
                && averageContrast >= LOW_CONTRAST_THRESHOLD) {
            findings.add(finding(
                    "animated_still",
                    "blocking",
                    "疑似动图化静帧",
                    "抽帧显示运动分布接近整幅画面同步漂移，缺少人物局部表演、肢体动作或真实视差，不能作为可发布短剧镜头。",
                    "switchWan",
                    metrics));
        }
        if (averageFrameDiff > SMEAR_DIFF_THRESHOLD
                && averageSharpness < LOW_SHARPNESS_THRESHOLD * 1.8d
                && averageContrast < LOW_CONTRAST_THRESHOLD * 2.2d) {
            findings.add(finding(
                    "motion_smear",
                    "warning",
                    "检测到拖影糊化",
                    "画面存在变化但细节持续偏低，疑似运动拖影、溶解或主体糊化。",
                    "retryVideo",
                    metrics));
        }

        return new VisualQualityReport(true, metrics, findings);
    }

    private void extractFrames(Path videoPath, Path tempDir, Integer expectedDurationSeconds) throws Exception {
        String outputPattern = tempDir.resolve("frame_%03d.jpg").toString();
        String fps = resolveSampleFps(expectedDurationSeconds);
        List<String> command = List.of(
                ffmpegPath,
                "-hide_banner",
                "-y",
                "-i", videoPath.toString(),
                "-vf", "fps=" + fps + ",scale=240:-1",
                "-frames:v", String.valueOf(MAX_SAMPLE_FRAMES),
                outputPattern
        );
        runProcess(command, Duration.ofSeconds(24));
    }

    private String resolveSampleFps(Integer expectedDurationSeconds) {
        int duration = expectedDurationSeconds == null || expectedDurationSeconds <= 0 ? 8 : expectedDurationSeconds;
        double fps = Math.min(8.0d, Math.max(4.0d, MAX_SAMPLE_FRAMES / (double) Math.max(duration, 1)));
        return String.format(Locale.ROOT, "%.3f", fps);
    }

    private List<BufferedImage> readFrames(Path tempDir) throws Exception {
        try (var stream = Files.list(tempDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jpg"))
                    .sorted()
                    .map(this::readImageQuietly)
                    .filter(image -> image != null)
                    .toList();
        }
    }

    private BufferedImage readImageQuietly(Path path) {
        try {
            return ImageIO.read(path.toFile());
        } catch (Exception e) {
            return null;
        }
    }

    private FrameMetrics measureFrame(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        double sum = 0d;
        double sumSquares = 0d;
        int count = 0;
        double edgeSum = 0d;
        int edgeCount = 0;

        for (int y = 1; y < height - 1; y += 2) {
            for (int x = 1; x < width - 1; x += 2) {
                double center = luminance(image.getRGB(x, y));
                sum += center;
                sumSquares += center * center;
                count++;

                double horizontal = Math.abs(luminance(image.getRGB(x + 1, y)) - luminance(image.getRGB(x - 1, y)));
                double vertical = Math.abs(luminance(image.getRGB(x, y + 1)) - luminance(image.getRGB(x, y - 1)));
                edgeSum += horizontal + vertical;
                edgeCount += 2;
            }
        }
        double mean = count == 0 ? 0d : sum / count;
        double variance = count == 0 ? 0d : Math.max(0d, (sumSquares / count) - mean * mean);
        double contrast = Math.sqrt(variance);
        double sharpness = edgeCount == 0 ? 0d : edgeSum / edgeCount;
        double colorfulness = measureColorfulness(image);
        return new FrameMetrics(sharpness, contrast, colorfulness);
    }

    private double measureColorfulness(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        double sum = 0d;
        int count = 0;
        for (int y = 0; y < height; y += 3) {
            for (int x = 0; x < width; x += 3) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                sum += (Math.abs(r - g) + Math.abs(g - b) + Math.abs(r - b)) / (3d * 255d);
                count++;
            }
        }
        return count == 0 ? 0d : sum / count;
    }

    private double frameDifference(BufferedImage first, BufferedImage second) {
        int width = Math.min(first.getWidth(), second.getWidth());
        int height = Math.min(first.getHeight(), second.getHeight());
        if (width <= 0 || height <= 0) {
            return 0d;
        }
        double sum = 0d;
        int count = 0;
        for (int y = 0; y < height; y += 3) {
            for (int x = 0; x < width; x += 3) {
                sum += Math.abs(luminance(first.getRGB(x, y)) - luminance(second.getRGB(x, y)));
                count++;
            }
        }
        return count == 0 ? 0d : sum / count;
    }

    private MotionProfile motionProfile(BufferedImage first, BufferedImage second) {
        int width = Math.min(first.getWidth(), second.getWidth());
        int height = Math.min(first.getHeight(), second.getHeight());
        if (width <= 0 || height <= 0) {
            return new MotionProfile(0d, 0d, 0d, 1d);
        }
        int cols = 6;
        int rows = 8;
        List<Double> cellDiffs = new ArrayList<>(cols * rows);
        double centerSum = 0d;
        int centerCount = 0;
        double borderSum = 0d;
        int borderCount = 0;

        for (int row = 0; row < rows; row++) {
            int y0 = row * height / rows;
            int y1 = Math.max(y0 + 1, (row + 1) * height / rows);
            for (int col = 0; col < cols; col++) {
                int x0 = col * width / cols;
                int x1 = Math.max(x0 + 1, (col + 1) * width / cols);
                double diff = cellDifference(first, second, x0, y0, x1, y1);
                cellDiffs.add(diff);
                boolean centerCell = col >= 2 && col <= 3 && row >= 2 && row <= 5;
                if (centerCell) {
                    centerSum += diff;
                    centerCount++;
                } else {
                    borderSum += diff;
                    borderCount++;
                }
            }
        }

        double mean = average(cellDiffs);
        double variance = cellDiffs.stream()
                .mapToDouble(value -> {
                    double delta = value - mean;
                    return delta * delta;
                })
                .average()
                .orElse(0d);
        double std = Math.sqrt(variance);
        double cv = mean <= 0.0001d ? 0d : std / mean;
        double activeRatio = cellDiffs.stream()
                .filter(value -> value >= GRID_ACTIVE_DIFF_THRESHOLD)
                .count() / (double) cellDiffs.size();
        double centerAverage = centerCount == 0 ? 0d : centerSum / centerCount;
        double borderAverage = borderCount == 0 ? 0d : borderSum / borderCount;
        double centerBorderRatio = borderAverage <= 0.0001d ? (centerAverage > 0d ? 9d : 1d) : centerAverage / borderAverage;
        double translationResidualRatio = translationCompensatedResidualRatio(first, second, width, height, mean);
        return new MotionProfile(activeRatio, cv, centerBorderRatio, translationResidualRatio);
    }

    private double cellDifference(BufferedImage first, BufferedImage second, int x0, int y0, int x1, int y1) {
        double sum = 0d;
        int count = 0;
        for (int y = y0; y < y1; y += 3) {
            for (int x = x0; x < x1; x += 3) {
                sum += Math.abs(luminance(first.getRGB(x, y)) - luminance(second.getRGB(x, y)));
                count++;
            }
        }
        return count == 0 ? 0d : sum / count;
    }

    private boolean isGlobalMotionLike(MotionProfile profile) {
        return profile != null
                && profile.activeCellRatio() >= 0.55d
                && profile.gridDiffCoefficientOfVariation() <= GRID_MOTION_CV_THRESHOLD
                && profile.centerToBorderDiffRatio() >= 0.55d
                && profile.centerToBorderDiffRatio() <= 1.85d;
    }

    private boolean hasLocalPerformanceEvidence(double gridMotionCv,
                                                double centerToBorderDiffRatio,
                                                double averageFrameDiff,
                                                double translationResidualRatio) {
        if (averageFrameDiff >= STRONG_ACTION_MIN_DIFF
                && translationResidualRatio >= TRANSLATION_RESIDUAL_ACTION_THRESHOLD) {
            return true;
        }
        if (gridMotionCv >= LOCAL_PERFORMANCE_CV_THRESHOLD) {
            return true;
        }
        if (centerToBorderDiffRatio >= LOCAL_PERFORMANCE_CENTER_RATIO_THRESHOLD
                && averageFrameDiff >= LOCAL_PERFORMANCE_MIN_DIFF) {
            return true;
        }
        return centerToBorderDiffRatio >= STRONG_SUBJECT_CENTER_RATIO_THRESHOLD
                && averageFrameDiff >= STRONG_SUBJECT_MIN_DIFF;
    }

    private String resolveLocalPerformanceEvidenceReason(double gridMotionCv,
                                                         double centerToBorderDiffRatio,
                                                         double averageFrameDiff,
                                                         double translationResidualRatio) {
        if (averageFrameDiff >= STRONG_ACTION_MIN_DIFF
                && translationResidualRatio >= TRANSLATION_RESIDUAL_ACTION_THRESHOLD) {
            return "translation_residual_subject_action";
        }
        if (gridMotionCv >= LOCAL_PERFORMANCE_CV_THRESHOLD) {
            return "grid_variation";
        }
        if (centerToBorderDiffRatio >= LOCAL_PERFORMANCE_CENTER_RATIO_THRESHOLD
                && averageFrameDiff >= LOCAL_PERFORMANCE_MIN_DIFF) {
            return "center_subject_motion";
        }
        if (centerToBorderDiffRatio >= STRONG_SUBJECT_CENTER_RATIO_THRESHOLD
                && averageFrameDiff >= STRONG_SUBJECT_MIN_DIFF) {
            return "strong_center_subject_motion";
        }
        return "none";
    }

    private double luminance(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return (0.2126d * r + 0.7152d * g + 0.0722d * b) / 255d;
    }

    private double translationCompensatedResidualRatio(BufferedImage first,
                                                       BufferedImage second,
                                                       int width,
                                                       int height,
                                                       double rawDiff) {
        if (rawDiff <= 0.0001d) {
            return 1d;
        }
        int maxShift = Math.max(4, Math.min(10, Math.min(width, height) / 18));
        double bestAlignedDiff = Double.MAX_VALUE;
        for (int dy = -maxShift; dy <= maxShift; dy += 2) {
            for (int dx = -maxShift; dx <= maxShift; dx += 2) {
                double sum = 0d;
                int count = 0;
                for (int y = maxShift; y < height - maxShift; y += 6) {
                    int shiftedY = y + dy;
                    if (shiftedY < 0 || shiftedY >= height) {
                        continue;
                    }
                    for (int x = maxShift; x < width - maxShift; x += 6) {
                        int shiftedX = x + dx;
                        if (shiftedX < 0 || shiftedX >= width) {
                            continue;
                        }
                        sum += Math.abs(luminance(first.getRGB(x, y)) - luminance(second.getRGB(shiftedX, shiftedY)));
                        count++;
                    }
                }
                if (count > 0) {
                    bestAlignedDiff = Math.min(bestAlignedDiff, sum / count);
                }
            }
        }
        if (bestAlignedDiff == Double.MAX_VALUE) {
            return 1d;
        }
        return bestAlignedDiff / Math.max(rawDiff, 0.0001d);
    }

    private VisualQualityFinding finding(String issueType,
                                         String severity,
                                         String title,
                                         String message,
                                         String recommendedAction,
                                         Map<String, Object> metrics) {
        return new VisualQualityFinding(issueType, severity, title, message, recommendedAction, Map.copyOf(metrics));
    }

    private double average(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0d;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
    }

    private double roundMetric(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private String runProcess(List<String> command, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (Exception ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("visual analysis process timeout");
        }
        reader.join(500);
        if (process.exitValue() != 0) {
            throw new IllegalStateException(output.toString());
        }
        return output.toString();
    }

    private void deleteQuietly(Path tempDir) {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var stream = Files.walk(tempDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record FrameMetrics(double sharpness, double contrast, double colorfulness) {
    }

    private record MotionProfile(double activeCellRatio,
                                 double gridDiffCoefficientOfVariation,
                                 double centerToBorderDiffRatio,
                                 double translationResidualRatio) {
    }
}
