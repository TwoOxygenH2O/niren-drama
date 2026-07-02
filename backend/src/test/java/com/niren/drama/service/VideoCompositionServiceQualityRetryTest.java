package com.niren.drama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.mapper.ProjectMapper;
import com.niren.drama.mapper.StoryboardMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoCompositionServiceQualityRetryTest {

    @Test
    void pollDynamicVideoTasksResubmitsShotOnceWhenGeneratedVideoFailsVisualQualityGate() {
        StoryboardMapper storyboardMapper = mock(StoryboardMapper.class);
        TaskRecordMapper taskRecordMapper = mock(TaskRecordMapper.class);
        AiVideoGenerationService aiVideoGenerationService = mock(AiVideoGenerationService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<VideoCompositionService> selfProvider = mock(ObjectProvider.class);
        VideoCompositionService service = new VideoCompositionService(
                mock(StoryboardService.class),
                storyboardMapper,
                mock(ProjectMapper.class),
                taskRecordMapper,
                aiVideoGenerationService,
                null,
                mock(PublicAssetStorageService.class),
                new ObjectMapper(),
                selfProvider,
                scheduler);
        ReflectionTestUtils.setField(service, "dynamicVideoTimeoutMinutes", 720L);
        ReflectionTestUtils.setField(service, "dynamicVideoPollBaseSeconds", 5L);
        ReflectionTestUtils.setField(service, "dynamicVideoPollMaxSeconds", 20L);
        ReflectionTestUtils.setField(service, "dynamicVideoQualityRetryMaxAttempts", 1);

        TaskRecord task = new TaskRecord();
        task.setId(77L);
        task.setProjectId(13L);
        task.setUserId(7L);
        task.setTaskType("DYNAMIC_VIDEO_GEN");
        task.setStatus("RUNNING");
        task.setProgress(50);
        task.setCreateTime(LocalDateTime.now().minusMinutes(1));

        Storyboard shot = new Storyboard();
        shot.setId(101L);
        shot.setProjectId(13L);
        shot.setShotNo(1);
        shot.setDuration(8);
        shot.setVideoTaskRecordId(77L);
        shot.setVideoTaskId("old-prompt");
        shot.setVideoTaskStatusUrl("http://127.0.0.1:8188/history/old-prompt");
        shot.setVideoTaskProvider("comfyui");
        shot.setVideoTaskStatus("submitted");
        shot.setStatus("video_submitted");
        shot.setVideoPrompt("老人看世界杯时识破诈骗电话");

        when(taskRecordMapper.selectById(77L)).thenReturn(task);
        when(storyboardMapper.selectList(any())).thenReturn(List.of(shot));
        when(aiVideoGenerationService.querySubmittedVideoTask(7L, shot)).thenReturn(
                new AiVideoGenerationService.VideoTaskQueryResult(
                        "comfyui",
                        "failed",
                        null,
                        "视频视觉质检未通过: washed_gray_video"));
        when(aiVideoGenerationService.submitVideoTask(7L, shot, null, false)).thenReturn(
                new AiVideoGenerationService.VideoTaskSubmission(
                        "comfyui",
                        "retry-prompt",
                        "http://127.0.0.1:8188/history/retry-prompt",
                        null));

        service.pollDynamicVideoTasksAsync(7L, 13L, 77L);

        assertThat(shot.getVideoTaskId()).isEqualTo("retry-prompt");
        assertThat(shot.getVideoTaskStatus()).isEqualTo("submitted");
        assertThat(shot.getStatus()).isEqualTo("video_submitted");
        assertThat(shot.getDynamicReason()).contains("视频质检自动重试1次");
        verify(storyboardMapper).updateById(shot);
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }
}
