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

    @Test
    void analyzeTreatsUnwatchableVisualAsBlocking() {
        Project project = new Project();
        project.setId(20L);
        project.setName("视觉质检短剧");

        Storyboard shot = shot(201L, 1, "/files/frame-1.png", "/files/video-1.mp4", "success", 5,
                "女主站在宫门外压抑落泪", "same face, same outfit, subtle motion");

        ProductionIssue visualIssue = issue(201L, "unwatchable_visual", "blocking", "retryVideo");
        visualIssue.setMetadata("""
                {"visualMetrics":{"sampledFrames":8,"averageSharpness":0.004,"averageContrast":0.02,"averageFrameDiff":0.001,"lowDetailRatio":1.0}}
                """);

        CasrAnalysisResult result = service.analyze(project,
                List.of(shot),
                List.of(visualIssue),
                List.of(),
                List.of());

        assertThat(result.getFailureTypes()).contains("unwatchable_visual");
        assertThat(result.getQualityScore()).isLessThan(70);
        assertThat(result.getOverallScore()).isLessThan(85);
        assertThat(result.getMetrics()).containsEntry("visualAnalysisEnforced", true);
        assertThat(result.getMetrics()).containsEntry("blockingVisualIssueCount", 1L);
        assertThat(result.getShotDiagnoses()).hasSize(1);
        assertThat(result.getShotDiagnoses().get(0).getSeverity()).isEqualTo("blocking");
        assertThat(result.getShotDiagnoses().get(0).getRecommendedAction()).isEqualTo("retryVideo");
    }

    @Test
    void analyzeConvertsVisualMetricsIntoCasrEvidence() {
        Project project = new Project();
        project.setId(21L);
        project.setName("动态证据短剧");

        Storyboard shot = shot(211L, 1, "/files/frame-1.png", "/files/video-1.mp4", "success", 5,
                "女主快速奔跑追出王府", "same face, same outfit, fast chase motion");

        ProductionIssue visualIssue = issue(211L, "weak_motion", "warning", "retryVideo");
        visualIssue.setMetadata("""
                {"visualMetrics":{"sampledFrames":8,"averageSharpness":0.04,"averageContrast":0.12,"averageFrameDiff":0.001,"lowDetailRatio":0.0}}
                """);

        CasrAnalysisResult result = service.analyze(project,
                List.of(shot),
                List.of(visualIssue),
                List.of(),
                List.of());

        assertThat(result.getFailureTypes()).contains("weak_motion", "motion_failure");
        assertThat(result.getMetrics()).containsEntry("visualEvidenceCoverage", 1.0d);
        assertThat(result.getShotDiagnoses().get(0).getFailureTypes()).contains("motion_failure");
    }

    @Test
    void analyzeTreatsAnimatedStillAsBlockingMotionFailure() {
        Project project = new Project();
        project.setId(22L);
        project.setName("动图化视频短剧");

        Storyboard shot = shot(221L, 1, "/files/frame-1.png", "/files/video-1.mp4", "success", 6,
                "女主在长街转身拔簪，宫灯晃动", "medium motion, body turn, cloth motion, no slideshow");

        ProductionIssue visualIssue = issue(221L, "animated_still", "blocking", "switchWan");
        visualIssue.setMetadata("""
                {"visualMetrics":{"sampledFrames":8,"averageFrameDiff":0.018,"globalMotionLikeRatio":0.82,"gridMotionCoefficientOfVariation":0.42}}
                """);

        CasrAnalysisResult result = service.analyze(project,
                List.of(shot),
                List.of(visualIssue),
                List.of(),
                List.of());

        assertThat(result.getFailureTypes()).contains("animated_still", "motion_failure");
        assertThat(result.getQualityScore()).isLessThan(75);
        assertThat(result.getMetrics()).containsEntry("blockingVisualIssueCount", 1L);
        assertThat(result.getShotDiagnoses().get(0).getSeverity()).isEqualTo("blocking");
        assertThat(result.getShotDiagnoses().get(0).getRecommendedAction()).isEqualTo("switchWan");
    }

    @Test
    void analyzeTreatsLowEffectiveFpsAsBlockingMotionFailure() {
        Project project = new Project();
        project.setId(24L);
        project.setName("低有效帧率短剧");

        Storyboard shot = shot(241L, 1, "/files/frame-1.png", "/files/video-1.mp4", "success", 8,
                "女主转身掀开账册，烛火摇动", "readable actor motion, no slideshow");

        ProductionIssue visualIssue = issue(241L, "low_effective_fps", "blocking", "retryVideo");
        visualIssue.setMetadata("""
                {"visualMetrics":{"sampledFrames":24,"duplicateAdjacentFrameRatio":0.52,"averageFrameDiff":0.03}}
                """);

        CasrAnalysisResult result = service.analyze(project,
                List.of(shot),
                List.of(visualIssue),
                List.of(),
                List.of());

        assertThat(result.getFailureTypes()).contains("low_effective_fps", "motion_failure");
        assertThat(result.getQualityScore()).isLessThan(75);
        assertThat(result.getMetrics()).containsEntry("blockingVisualIssueCount", 1L);
        assertThat(result.getShotDiagnoses().get(0).getSeverity()).isEqualTo("blocking");
        assertThat(result.getShotDiagnoses().get(0).getRecommendedAction()).isEqualTo("retryVideo");
    }

    @Test
    void analyzeTreatsVlmReviewIssuesAsVisualBlockingFailures() {
        Project project = new Project();
        project.setId(23L);
        project.setName("VLM 逐镜审片短剧");

        Storyboard shot = shot(231L, 1, "/files/frame-1.png", "/files/video-1.mp4", "success", 6,
                "女主在王府门前转身拔簪，眼神决绝", "same identity, same costume, body turn, publishable face");

        ProductionIssue identity = issue(231L, "identity_drift", "blocking", "retryVideo");
        identity.setMetadata("""
                {"visualAnalyzer":"vlm_keyframe_review","vlmScores":{"identityConsistency":42,"faceQuality":88,"motionPerformance":73,"storyboardMatch":80}}
                """);
        ProductionIssue action = issue(231L, "action_mismatch", "warning", "retryVideo");

        CasrAnalysisResult result = service.analyze(project,
                List.of(shot),
                List.of(identity, action),
                List.of(),
                List.of());

        assertThat(result.getFailureTypes()).contains("identity_drift", "action_mismatch");
        assertThat(result.getQualityScore()).isLessThan(75);
        assertThat(result.getMetrics()).containsEntry("visualAnalysisEnforced", true);
        assertThat(result.getMetrics()).containsEntry("blockingVisualIssueCount", 1L);
        assertThat(result.getShotDiagnoses()).hasSize(1);
        assertThat(result.getShotDiagnoses().get(0).getFailureTypes()).contains("identity_drift", "action_mismatch");
        assertThat(result.getShotDiagnoses().get(0).getSeverity()).isEqualTo("blocking");
        assertThat(result.getShotDiagnoses().get(0).getRecommendedAction()).isEqualTo("retryVideo");
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
