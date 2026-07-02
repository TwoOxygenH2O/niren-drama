package com.niren.drama.service;

import com.niren.drama.entity.ConsistencyBible;
import com.niren.drama.entity.ProductionIssue;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.twooxygen.casr.domain.CasrAnalysisResult;
import com.twooxygen.casr.domain.CasrShotDiagnosis;
import com.twooxygen.casr.engine.CasrEngine;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CasrAnalysisService {

    private static final Set<String> VISUAL_ISSUE_TYPES = Set.of(
            "low_visual_detail",
            "unwatchable_visual",
            "weak_motion",
            "animated_still",
            "low_effective_fps",
            "motion_smear",
            "first_frame_drift_risk",
            "identity_drift",
            "wardrobe_inconsistent",
            "face_broken",
            "action_mismatch",
            "unpublishable_frame",
            "storyboard_mismatch",
            "reference_mismatch"
    );
    private static final Set<String> BLOCKING_VISUAL_ISSUE_TYPES = Set.of(
            "unwatchable_visual",
            "animated_still",
            "low_effective_fps",
            "identity_drift",
            "face_broken",
            "unpublishable_frame"
    );
    private static final Map<String, Integer> VISUAL_QUALITY_PENALTIES = Map.ofEntries(
            Map.entry("unwatchable_visual", 36),
            Map.entry("low_visual_detail", 14),
            Map.entry("weak_motion", 10),
            Map.entry("animated_still", 30),
            Map.entry("low_effective_fps", 30),
            Map.entry("motion_smear", 12),
            Map.entry("first_frame_drift_risk", 16),
            Map.entry("identity_drift", 34),
            Map.entry("wardrobe_inconsistent", 18),
            Map.entry("face_broken", 36),
            Map.entry("action_mismatch", 20),
            Map.entry("unpublishable_frame", 40),
            Map.entry("storyboard_mismatch", 18),
            Map.entry("reference_mismatch", 24)
    );

    private final NirenCasrInputAdapter inputAdapter;
    private final CasrEngine casrEngine;

    public CasrAnalysisService(NirenCasrInputAdapter inputAdapter) {
        this.inputAdapter = inputAdapter;
        this.casrEngine = new CasrEngine();
    }

    public CasrAnalysisResult analyze(Project project,
                                      List<Storyboard> shots,
                                      List<ProductionIssue> issues,
                                      List<ConsistencyBible> consistencyItems,
                                      List<TaskRecord> activeTasks) {
        CasrAnalysisResult result = casrEngine.analyze(inputAdapter.toInput(project, shots, issues, consistencyItems, activeTasks));
        applyVisualIssueGate(result, issues);
        return result;
    }

    private void applyVisualIssueGate(CasrAnalysisResult result, List<ProductionIssue> issues) {
        if (result == null || issues == null || issues.isEmpty()) {
            return;
        }
        List<ProductionIssue> visualIssues = issues.stream()
                .filter(this::isActiveVisualIssue)
                .toList();
        if (visualIssues.isEmpty()) {
            return;
        }

        LinkedHashSet<String> failureTypes = new LinkedHashSet<>(safe(result.getFailureTypes()));
        visualIssues.stream()
                .map(ProductionIssue::getIssueType)
                .filter(this::hasText)
                .forEach(failureTypes::add);
        result.setFailureTypes(new ArrayList<>(failureTypes));

        Map<Long, List<ProductionIssue>> issuesByShot = visualIssues.stream()
                .filter(issue -> issue.getShotId() != null)
                .collect(Collectors.groupingBy(ProductionIssue::getShotId));
        for (CasrShotDiagnosis diagnosis : safe(result.getShotDiagnoses())) {
            List<ProductionIssue> shotIssues = issuesByShot.getOrDefault(diagnosis.getShotId(), List.of());
            if (!shotIssues.isEmpty()) {
                applyVisualIssueGateToShot(diagnosis, shotIssues);
            }
        }

        int visualPenalty = Math.min(70, visualIssues.stream()
                .mapToInt(issue -> visualPenalty(issue.getIssueType()))
                .sum());
        result.setQualityScore(Math.min(result.getQualityScore(), clamp(100 - visualPenalty)));
        result.setOverallScore(clamp((int) Math.round(result.getQualityScore() * 0.55d
                + result.getContinuityScore() * 0.45d)));

        long blockingVisualIssues = visualIssues.stream().filter(this::isBlockingVisualIssue).count();
        Map<String, Object> metrics = new LinkedHashMap<>(result.getMetrics() == null ? Map.of() : result.getMetrics());
        metrics.put("visualAnalysisEnforced", true);
        metrics.put("visualIssueCount", (long) visualIssues.size());
        metrics.put("blockingVisualIssueCount", blockingVisualIssues);
        result.setMetrics(metrics);

        if (!hasText(result.getSummary())) {
            result.setSummary("CASR includes visual frame quality gates.");
        } else if (!result.getSummary().contains("visual frame quality")) {
            result.setSummary(result.getSummary() + " Visual frame quality gates are enforced.");
        }
    }

    private void applyVisualIssueGateToShot(CasrShotDiagnosis diagnosis, List<ProductionIssue> shotIssues) {
        LinkedHashSet<String> shotFailureTypes = new LinkedHashSet<>(safe(diagnosis.getFailureTypes()));
        shotIssues.stream()
                .map(ProductionIssue::getIssueType)
                .filter(this::hasText)
                .forEach(shotFailureTypes::add);
        diagnosis.setFailureTypes(new ArrayList<>(shotFailureTypes));

        int visualPenalty = Math.min(70, shotIssues.stream()
                .mapToInt(issue -> visualPenalty(issue.getIssueType()))
                .sum());
        diagnosis.setQualityScore(Math.min(diagnosis.getQualityScore(), clamp(100 - visualPenalty)));
        if (shotIssues.stream().anyMatch(this::isBlockingVisualIssue)) {
            diagnosis.setSeverity("blocking");
        } else if (!"blocking".equals(diagnosis.getSeverity())) {
            diagnosis.setSeverity("warning");
        }
        diagnosis.setRecommendedAction(resolveVisualRecommendedAction(shotIssues));
        diagnosis.setEstimatedMinutes(Math.max(diagnosis.getEstimatedMinutes(), 6));
        if (!hasText(diagnosis.getExplanation())) {
            diagnosis.setExplanation("Visual frame quality issue detected. Recommended action: "
                    + diagnosis.getRecommendedAction() + ".");
        } else if (!diagnosis.getExplanation().contains("Visual frame quality")) {
            diagnosis.setExplanation(diagnosis.getExplanation()
                    + " Visual frame quality issue detected; recommended action: "
                    + diagnosis.getRecommendedAction() + ".");
        }
    }

    private boolean isActiveVisualIssue(ProductionIssue issue) {
        return issue != null
                && VISUAL_ISSUE_TYPES.contains(text(issue.getIssueType()))
                && !"resolved".equalsIgnoreCase(text(issue.getStatus()));
    }

    private boolean isBlockingVisualIssue(ProductionIssue issue) {
        return issue != null
                && (BLOCKING_VISUAL_ISSUE_TYPES.contains(text(issue.getIssueType()))
                || "blocking".equalsIgnoreCase(text(issue.getSeverity())));
    }

    private String resolveVisualRecommendedAction(List<ProductionIssue> issues) {
        return issues.stream()
                .map(ProductionIssue::getRecommendedAction)
                .filter(this::hasText)
                .findFirst()
                .orElse("retryVideo");
    }

    private int visualPenalty(String issueType) {
        return VISUAL_QUALITY_PENALTIES.getOrDefault(text(issueType), 8);
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
