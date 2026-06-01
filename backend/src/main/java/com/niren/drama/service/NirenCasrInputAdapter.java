package com.niren.drama.service;

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
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class NirenCasrInputAdapter {

    public CasrInput toInput(Project project,
                             List<Storyboard> shots,
                             List<ProductionIssue> issues,
                             List<ConsistencyBible> consistencyItems,
                             List<TaskRecord> tasks) {
        CasrInput input = new CasrInput();
        if (project != null) {
            input.setProject(new CasrProjectInput(project.getId(), project.getName(), project.getProjectType()));
        }
        input.setShots(safe(shots).stream()
                .sorted(Comparator.comparing(Storyboard::getShotNo, Comparator.nullsLast(Integer::compareTo)))
                .map(this::toShotInput)
                .toList());
        input.setIssues(safe(issues).stream().map(this::toIssueInput).toList());
        input.setConsistencyAnchors(safe(consistencyItems).stream().map(this::toAnchor).toList());
        input.setTasks(safe(tasks).stream().map(this::toTaskInput).toList());
        return input;
    }

    private CasrShotInput toShotInput(Storyboard shot) {
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
        return input;
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
}
