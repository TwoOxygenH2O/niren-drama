package com.niren.drama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EpisodePipelineService {

    private static final Duration DEFAULT_STEP_TIMEOUT = Duration.ofHours(12);

    private final ProjectService projectService;
    private final StoryboardService storyboardService;
    private final VideoCompositionService videoCompositionService;
    private final ProductionWorkspaceService productionWorkspaceService;
    private final TaskRecordMapper taskRecordMapper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<EpisodePipelineService> selfProvider;

    public TaskRecord startPipeline(Long userId,
                                    Long projectId,
                                    Integer episodeNo,
                                    List<Long> shotIds,
                                    VideoCompositionService.ComposeOptions composeOptions,
                                    boolean runQualityCheck) {
        projectService.getProject(userId, projectId);
        List<Storyboard> shots = selectShots(projectId, episodeNo, shotIds);
        if (shots.isEmpty()) {
            throw new BusinessException("没有可执行生产线的分镜，请先生成分镜");
        }

        TaskRecord task = new TaskRecord();
        task.setProjectId(projectId);
        task.setUserId(userId);
        task.setTaskType("EPISODE_PIPELINE");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("剧集生产线任务已提交...");
        taskRecordMapper.insert(task);

        selfProvider.getObject().runPipelineAsync(
                userId, projectId, episodeNo, shotIds, composeOptions, runQualityCheck, task.getId());
        return task;
    }

    @Async("aiTaskExecutor")
    public void runPipelineAsync(Long userId,
                                 Long projectId,
                                 Integer episodeNo,
                                 List<Long> shotIds,
                                 VideoCompositionService.ComposeOptions composeOptions,
                                 boolean runQualityCheck,
                                 Long pipelineTaskId) {
        TaskRecord pipelineTask = taskRecordMapper.selectById(pipelineTaskId);
        if (pipelineTask == null) {
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            updateTask(pipelineTask, "RUNNING", 5, "正在检查本集分镜素材...");
            List<Storyboard> shots = selectShots(projectId, episodeNo, shotIds);
            if (shots.isEmpty()) {
                throw new BusinessException("没有可执行生产线的分镜，请先生成分镜");
            }
            List<Long> selectedShotIds = shots.stream().map(Storyboard::getId).toList();
            result.put("shotIds", selectedShotIds);
            result.put("episodeNo", episodeNo);

            if (shots.stream().anyMatch(shot -> !hasText(shot.getImageUrl()))) {
                updateTask(pipelineTask, "RUNNING", 12, "正在生成缺失首帧图片...");
                TaskRecord imageTask = storyboardService.startGenerateStoryboardImages(userId, projectId, selectedShotIds);
                waitForChildTask(imageTask.getId(), "首帧图片生成", DEFAULT_STEP_TIMEOUT);
                result.put("imageTaskId", imageTask.getId());
            }

            updateTask(pipelineTask, "RUNNING", 34, "正在生成动态镜头...");
            TaskRecord dynamicTask = videoCompositionService.startGenerateDynamicVideos(userId, projectId, selectedShotIds);
            waitForChildTask(dynamicTask.getId(), "动态镜头生成", DEFAULT_STEP_TIMEOUT);
            result.put("dynamicTaskId", dynamicTask.getId());

            updateTask(pipelineTask, "RUNNING", 58, "正在生成分镜配音...");
            TaskRecord audioTask = storyboardService.startGenerateStoryboardAudio(userId, projectId, selectedShotIds);
            waitForChildTask(audioTask.getId(), "分镜配音生成", DEFAULT_STEP_TIMEOUT);
            result.put("audioTaskId", audioTask.getId());

            updateTask(pipelineTask, "RUNNING", 78, "正在合成本集成片...");
            TaskRecord composeTask = videoCompositionService.startCompose(userId, projectId, selectedShotIds, composeOptions);
            waitForChildTask(composeTask.getId(), "视频合成", DEFAULT_STEP_TIMEOUT);
            result.put("composeTaskId", composeTask.getId());
            TaskRecord finishedCompose = taskRecordMapper.selectById(composeTask.getId());
            if (finishedCompose != null) {
                result.put("videoUrl", videoCompositionService.extractVideoUrl(finishedCompose.getResult()));
            }

            if (runQualityCheck) {
                updateTask(pipelineTask, "RUNNING", 92, "正在运行本地质检...");
                result.put("quality", productionWorkspaceService.runQualityCheck(userId, projectId, Map.of(
                        "episodeNo", episodeNo == null ? "" : episodeNo,
                        "shotIds", selectedShotIds
                )));
            }

            pipelineTask.setResult(toJson(result));
            updateTask(pipelineTask, "SUCCESS", 100, "剧集生产线执行完成");
        } catch (Exception e) {
            log.error("剧集生产线执行失败: taskId={}, projectId={}", pipelineTaskId, projectId, e);
            pipelineTask.setResult(toJson(result));
            updateTask(pipelineTask, "FAILED", pipelineTask.getProgress() == null ? 0 : pipelineTask.getProgress(),
                    "剧集生产线执行失败: " + e.getMessage());
        }
    }

    private void waitForChildTask(Long taskId, String label, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            TaskRecord task = taskRecordMapper.selectById(taskId);
            if (task == null) {
                throw new BusinessException(label + "任务不存在");
            }
            if ("SUCCESS".equalsIgnoreCase(task.getStatus())) {
                return;
            }
            if ("FAILED".equalsIgnoreCase(task.getStatus())) {
                throw new BusinessException(label + "失败：" + (hasText(task.getMessage()) ? task.getMessage() : "未知错误"));
            }
            Thread.sleep(3000L);
        }
        throw new BusinessException(label + "等待超时");
    }

    private List<Storyboard> selectShots(Long projectId, Integer episodeNo, List<Long> shotIds) {
        List<Storyboard> shots = new ArrayList<>(storyboardService.listByProject(projectId));
        if (episodeNo != null) {
            shots = shots.stream()
                    .filter(shot -> episodeNo.equals(shot.getEpisodeNo()))
                    .toList();
        }
        if (shotIds != null && !shotIds.isEmpty()) {
            shots = shots.stream()
                    .filter(shot -> shot.getId() != null && shotIds.contains(shot.getId()))
                    .toList();
        }
        return shots;
    }

    private void updateTask(TaskRecord task, String status, int progress, String message) {
        task.setStatus(status);
        task.setProgress(Math.max(0, Math.min(100, progress)));
        task.setMessage(message);
        taskRecordMapper.updateById(task);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
