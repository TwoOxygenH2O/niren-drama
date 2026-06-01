package com.niren.drama.service;

import com.niren.drama.entity.ConsistencyBible;
import com.niren.drama.entity.ProductionIssue;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.twooxygen.casr.domain.CasrAnalysisResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CasrAnalysisServiceTest {

    private final CasrAnalysisService service = new CasrAnalysisService(new NirenCasrInputAdapter());

    @Test
    void analyzeDetectsContinuityAndQualityFailures() {
        Project project = new Project();
        project.setId(10L);
        project.setName("CASR 面试演示短剧");

        Storyboard missingFrame = shot(101L, 1, null, null, "submitted", 5,
                "女主站在雨夜巷口", "首帧已确定，保持同一张脸、同一服装。");
        Storyboard failedVideo = shot(102L, 2, "/files/frame-2.png", null, "failed", 5,
                "女主追出巷口", "快速奔跑，禁止换人换衣。");
        Storyboard riskyPrompt = shot(103L, 3, "/files/frame-3.png", "/files/video-3.mp4", "success", 12,
                "男主回头", "镜头快速推进");

        ProductionIssue blackFrame = issue(103L, "black_frame", "blocking", "retryVideo");
        ProductionIssue duration = issue(103L, "duration_out_of_range", "warning", "retryVideo");

        ConsistencyBible bible = new ConsistencyBible();
        bible.setBibleType("character");
        bible.setTitle("女主身份锚点");
        bible.setLockedAttributes("同一张脸、白色风衣、雨夜冷光");

        TaskRecord staleTask = new TaskRecord();
        staleTask.setTaskType("DYNAMIC_VIDEO_GEN");
        staleTask.setStatus("RUNNING");
        staleTask.setUpdateTime(LocalDateTime.now().minusHours(8));

        CasrAnalysisResult result = service.analyze(project,
                List.of(missingFrame, failedVideo, riskyPrompt),
                List.of(blackFrame, duration),
                List.of(bible),
                List.of(staleTask));

        assertThat(result.getProjectId()).isEqualTo(10L);
        assertThat(result.getFailureTypes()).contains(
                "missing_first_frame",
                "video_task_failed",
                "black_frame",
                "duration_out_of_range",
                "identity_drift_risk",
                "stale_task");
        assertThat(result.getQualityScore()).isLessThan(80);
        assertThat(result.getContinuityScore()).isLessThan(90);
        assertThat(result.getShotDiagnoses()).hasSize(3);
        assertThat(result.getShotDiagnoses().get(0).getRecommendedAction()).isEqualTo("regenerateFirstFrame");
    }

    private Storyboard shot(Long id,
                            int shotNo,
                            String imageUrl,
                            String videoUrl,
                            String videoTaskStatus,
                            int duration,
                            String description,
                            String videoPrompt) {
        Storyboard shot = new Storyboard();
        shot.setId(id);
        shot.setEpisodeNo(1);
        shot.setShotNo(shotNo);
        shot.setImageUrl(imageUrl);
        shot.setVideoUrl(videoUrl);
        shot.setVideoTaskStatus(videoTaskStatus);
        shot.setDuration(duration);
        shot.setDescription(description);
        shot.setVideoPrompt(videoPrompt);
        shot.setMotionTier("A");
        return shot;
    }

    private ProductionIssue issue(Long shotId, String type, String severity, String action) {
        ProductionIssue issue = new ProductionIssue();
        issue.setShotId(shotId);
        issue.setIssueType(type);
        issue.setSeverity(severity);
        issue.setRecommendedAction(action);
        issue.setStatus("open");
        return issue;
    }
}
