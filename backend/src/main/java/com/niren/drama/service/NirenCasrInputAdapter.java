package com.niren.drama.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.ConsistencyBible;
import com.niren.drama.entity.ProductionIssue;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.twooxygen.casr.domain.CasrConsistencyAnchor;
import com.twooxygen.casr.domain.CasrInput;
import com.twooxygen.casr.domain.CasrIssueInput;
import com.twooxygen.casr.domain.CasrProjectInput;
import com.twooxygen.casr.domain.CasrShotInput;
import com.twooxygen.casr.domain.CasrTaskInput;
import com.twooxygen.casr.domain.CasrVisualEvidence;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class NirenCasrInputAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public CasrInput toInput(Project project,
                             List<Storyboard> shots,
                             List<ProductionIssue> issues,
                             List<ConsistencyBible> consistencyItems,
                             List<TaskRecord> tasks) {
        CasrInput input = new CasrInput();
        if (project != null) {
            input.setProject(new CasrProjectInput(project.getId(), project.getName(), project.getProjectType()));
        }
        Map<Long, List<ProductionIssue>> issuesByShot = safe(issues).stream()
                .filter(issue -> issue.getShotId() != null)
                .collect(Collectors.groupingBy(ProductionIssue::getShotId));
        input.setShots(safe(shots).stream()
                .sorted(Comparator.comparing(Storyboard::getShotNo, Comparator.nullsLast(Integer::compareTo)))
                .map(shot -> toShotInput(shot, issuesByShot.getOrDefault(shot.getId(), List.of())))
                .toList());
        input.setIssues(safe(issues).stream().map(this::toIssueInput).toList());
        input.setConsistencyAnchors(safe(consistencyItems).stream().map(this::toAnchor).toList());
        input.setTasks(safe(tasks).stream().map(this::toTaskInput).toList());
        return input;
    }

    private CasrShotInput toShotInput(Storyboard shot, List<ProductionIssue> issues) {
        CasrShotInput input = new CasrShotInput();
        input.setShotId(shot.getId());
        input.setShotNo(shot.getShotNo());
        input.setDescription(shot.getDescription());
        input.setImageUrl(shot.getImageUrl());
        input.setVideoUrl(shot.getVideoUrl());
        input.setAudioUrl(shot.getAudioUrl());
        input.setDurationSeconds(shot.getDuration());
        input.setVideoTaskStatus(shot.getVideoTaskStatus());
        input.setImagePrompt(shot.getImagePrompt());
        input.setVideoPrompt(shot.getVideoPrompt());
        input.setMotionTier(shot.getMotionTier());
        input.setAspectRatio("9:16");
        input.setVisualEvidence(toVisualEvidence(issues));
        return input;
    }

    private CasrVisualEvidence toVisualEvidence(List<ProductionIssue> issues) {
        CasrVisualEvidence evidence = new CasrVisualEvidence();
        boolean present = false;
        for (ProductionIssue issue : safe(issues)) {
            String issueType = text(issue.getIssueType());
            if ("black_frame".equals(issueType)) {
                setMaxBlackFrameRatio(evidence, 1.0d);
                present = true;
            }
            if ("frozen_frame".equals(issueType)) {
                setMaxFrozenFrameRatio(evidence, 1.0d);
                present = true;
            }
            if ("wrong_aspect_ratio".equals(issueType)) {
                setMinAspectRatioConfidence(evidence, 0.0d);
                present = true;
            }
            if ("weak_motion".equals(issueType) || "animated_still".equals(issueType)
                    || "low_effective_fps".equals(issueType)
                    || "action_mismatch".equals(issueType) || "storyboard_mismatch".equals(issueType)) {
                setMinMotionMagnitude(evidence, 0.0d);
                present = true;
            }

            JsonNode metadata = parseMetadata(issue.getMetadata());
            if (metadata == null) {
                continue;
            }
            Double blackFrameRatio = firstDouble(metadata, "blackFrameRatio");
            if (blackFrameRatio != null) {
                setMaxBlackFrameRatio(evidence, blackFrameRatio);
                present = true;
            }
            Double frozenFrameRatio = firstDouble(metadata, "frozenFrameRatio");
            if (frozenFrameRatio != null) {
                setMaxFrozenFrameRatio(evidence, frozenFrameRatio);
                present = true;
            }
            Double motionMagnitude = firstDouble(metadata, "motionMagnitude", "averageFrameDiff");
            if (motionMagnitude != null) {
                setMinMotionMagnitude(evidence, motionMagnitude);
                present = true;
            }
            Double aspectRatioConfidence = firstDouble(metadata, "aspectRatioConfidence");
            if (aspectRatioConfidence != null) {
                setMinAspectRatioConfidence(evidence, aspectRatioConfidence);
                present = true;
            }
        }
        return present ? evidence : null;
    }

    private JsonNode parseMetadata(String metadata) {
        if (!hasText(metadata)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(metadata);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double firstDouble(JsonNode root, String... names) {
        for (String name : names) {
            Double value = doubleValue(root.path(name));
            if (value != null) {
                return value;
            }
        }
        JsonNode visualMetrics = root.path("visualMetrics");
        for (String name : names) {
            Double value = doubleValue(visualMetrics.path(name));
            if (value != null) {
                return value;
            }
        }
        JsonNode finding = root.path("finding");
        for (String name : names) {
            Double value = doubleValue(finding.path(name));
            if (value != null) {
                return value;
            }
        }
        JsonNode vlmScores = root.path("vlmReview").path("vlmScores");
        for (String name : names) {
            Double value = doubleValue(vlmScores.path(name));
            if (value != null) {
                return value;
            }
        }
        JsonNode findingVlmScores = finding.path("vlmScores");
        for (String name : names) {
            Double value = doubleValue(findingVlmScores.path(name));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Double doubleValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual() && hasText(node.asText())) {
            try {
                return Double.parseDouble(node.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void setMaxBlackFrameRatio(CasrVisualEvidence evidence, double value) {
        evidence.setBlackFrameRatio(evidence.getBlackFrameRatio() == null
                ? value
                : Math.max(evidence.getBlackFrameRatio(), value));
    }

    private void setMaxFrozenFrameRatio(CasrVisualEvidence evidence, double value) {
        evidence.setFrozenFrameRatio(evidence.getFrozenFrameRatio() == null
                ? value
                : Math.max(evidence.getFrozenFrameRatio(), value));
    }

    private void setMinMotionMagnitude(CasrVisualEvidence evidence, double value) {
        evidence.setMotionMagnitude(evidence.getMotionMagnitude() == null
                ? value
                : Math.min(evidence.getMotionMagnitude(), value));
    }

    private void setMinAspectRatioConfidence(CasrVisualEvidence evidence, double value) {
        evidence.setAspectRatioConfidence(evidence.getAspectRatioConfidence() == null
                ? value
                : Math.min(evidence.getAspectRatioConfidence(), value));
    }

    private CasrIssueInput toIssueInput(ProductionIssue issue) {
        CasrIssueInput input = new CasrIssueInput();
        input.setIssueId(issue.getId());
        input.setShotId(issue.getShotId());
        input.setIssueType(issue.getIssueType());
        input.setSeverity(issue.getSeverity());
        input.setStatus(issue.getStatus());
        return input;
    }

    private CasrConsistencyAnchor toAnchor(ConsistencyBible item) {
        CasrConsistencyAnchor input = new CasrConsistencyAnchor();
        input.setAnchorId(item.getId());
        input.setAnchorType(item.getBibleType());
        input.setName(item.getTitle());
        input.setLockedAttributes(item.getLockedAttributes());
        input.setPromptHints(item.getNotes());
        input.setLocked(Boolean.TRUE.equals(item.getLocked()));
        return input;
    }

    private CasrTaskInput toTaskInput(TaskRecord task) {
        CasrTaskInput input = new CasrTaskInput();
        input.setTaskId(task.getId());
        input.setTaskType(task.getTaskType());
        input.setStatus(task.getStatus());
        input.setUpdatedAt(task.getUpdateTime());
        return input;
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
