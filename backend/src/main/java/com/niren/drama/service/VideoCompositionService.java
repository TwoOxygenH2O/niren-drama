package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.trace.AiCallTrace;
import com.niren.drama.ai.trace.AiTraceContext;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.ProjectMapper;
import com.niren.drama.mapper.StoryboardMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/**
 * Video composition service using FFmpeg.
 * Assembles storyboard shots (images + audio) into a final video.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoCompositionService {

    private static final int MAX_TASK_TRACE_CALLS = 20;
    private static final Duration DYNAMIC_VIDEO_TASK_TIMEOUT = Duration.ofMinutes(10);
    private static final long DYNAMIC_VIDEO_POLL_DELAY_SECONDS = 5L;

    private final StoryboardService storyboardService;
    private final StoryboardMapper storyboardMapper;
    private final ProjectMapper projectMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final AiVideoGenerationService aiVideoGenerationService;
    private final PublicAssetStorageService publicAssetStorageService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<VideoCompositionService> selfProvider;
    private final TaskScheduler aiPollScheduler;

    @Value("${niren.upload.path:./uploads}")
    private String uploadPath;

    @Value("${niren.upload.base-url:http://localhost:8080/api/files}")
    private String baseUrl;

    @Value("${niren.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    private volatile String resolvedFfmpegExecutable;

    private static final String SHOT_VIDEO_DIR = "shot-videos";

    /**
     * Start the video composition process for a project.
     */
    public TaskRecord startCompose(Long userId, Long projectId, java.util.List<Long> shotIds) {
        List<Storyboard> shots = storyboardService.listByProject(projectId);
        if (shotIds != null && !shotIds.isEmpty()) {
            shots = shots.stream().filter(s -> shotIds.contains(s.getId())).collect(java.util.stream.Collectors.toList());
        }
        if (shots.isEmpty()) {
            throw new BusinessException("项目下没有分镜数据，请先生成分镜");
        }

        // Validate that shots have images
        boolean hasImages = shots.stream().anyMatch(s -> s.getImageUrl() != null && !s.getImageUrl().isBlank());
        if (!hasImages) {
            throw new BusinessException("分镜还没有生成图片，请先生成分镜图片");
        }

        log.debug("Creating video compose task: userId={}, projectId={}, shotCount={}, filteredByIds={}",
            userId, projectId, shots.size(), shotIds != null && !shotIds.isEmpty());

        TaskRecord task = new TaskRecord();
        task.setProjectId(projectId);
        task.setUserId(userId);
        task.setTaskType("VIDEO_COMPOSE");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("视频合成任务已提交...");
        taskRecordMapper.insert(task);

        selfProvider.getObject().composeAsync(userId, projectId, shots, task.getId());
        return task;
    }

    public TaskRecord startGenerateDynamicVideos(Long userId, Long projectId, java.util.List<Long> shotIds) {
        java.util.List<com.niren.drama.entity.Storyboard> allShots = storyboardService.listByProject(projectId);
        if (shotIds != null && !shotIds.isEmpty()) {
            allShots = allShots.stream().filter(s -> shotIds.contains(s.getId())).toList();
        }
        List<com.niren.drama.entity.Storyboard> selectedShots = allShots.stream()

                .filter(this::shouldUseDynamicVideo)
                .toList();

        if (selectedShots.isEmpty()) {
            throw new BusinessException("当前项目没有选中的动态镜头");
        }

        boolean allReadyForDynamic = selectedShots.stream().allMatch(s -> hasText(s.getVideoPrompt()) || hasText(s.getDescription()));
        if (!allReadyForDynamic) {
            throw new BusinessException("仍有已选动态镜头缺少视频提示词，请先完成分镜生成或补全镜头描述");
        }

        log.debug("Creating dynamic video task: userId={}, projectId={}, selectedShots={}, filteredByIds={}",
            userId, projectId, selectedShots.size(), shotIds != null && !shotIds.isEmpty());

        TaskRecord task = new TaskRecord();
        task.setProjectId(projectId);
        task.setUserId(userId);
        task.setTaskType("DYNAMIC_VIDEO_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("动态镜头生成任务已提交...");
        taskRecordMapper.insert(task);

        selfProvider.getObject().generateDynamicVideosAsync(userId, projectId, selectedShots, task.getId());
        return task;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumePendingDynamicVideoTasks() {
        List<TaskRecord> tasks = taskRecordMapper.selectList(
                new LambdaQueryWrapper<TaskRecord>()
                        .eq(TaskRecord::getTaskType, "DYNAMIC_VIDEO_GEN")
                        .in(TaskRecord::getStatus, "PENDING", "RUNNING"));
        for (TaskRecord task : tasks) {
            long pendingCount = storyboardMapper.selectCount(
                    new LambdaQueryWrapper<Storyboard>()
                            .eq(Storyboard::getVideoTaskRecordId, task.getId())
                            .and(wrapper -> wrapper
                                    .eq(Storyboard::getStatus, "video_submitted")
                                    .or()
                                    .eq(Storyboard::getStatus, "video_polling")));
            if (pendingCount > 0) {
                log.info("Resuming pending dynamic video polling: taskId={}, projectId={}, pendingShots={}",
                        task.getId(), task.getProjectId(), pendingCount);
                scheduleDynamicVideoPoll(task.getUserId(), task.getProjectId(), task.getId(), DYNAMIC_VIDEO_POLL_DELAY_SECONDS);
            }
        }
    }

    @Async("aiTaskExecutor")
    public void composeAsync(Long userId, Long projectId, List<Storyboard> shots, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;

        Path workDir = null;
        try {
            log.debug("Video composition start: taskId={}, userId={}, projectId={}, shotCount={}",
                    taskId, userId, projectId, shots.size());
            // Create working directory
            workDir = Paths.get(uploadPath, "compose", projectId.toString());
            Files.createDirectories(workDir);

            Path videoDir = Paths.get(uploadPath, "videos");
            Files.createDirectories(videoDir);

            updateTask(task, "RUNNING", 5, "准备合成素材...");

            // Step 1: Download images to local files
            List<Path> shotVideos = new ArrayList<>();
            int total = shots.size();
            int index = 0;

            for (Storyboard shot : shots) {
                index++;
                if (!hasText(shot.getImageUrl()) && !hasText(shot.getVideoUrl())) {
                    log.warn("Shot {} has no image, skipping", shot.getShotNo());
                    continue;
                }

                log.debug("Composing shot video: taskId={}, projectId={}, shotId={}, shotNo={}, renderMode={}, hasImage={}, hasVideo={}, hasAudio={}, duration={}",
                        taskId,
                        projectId,
                        shot.getId(),
                        shot.getShotNo(),
                        shot.getRenderMode(),
                        hasText(shot.getImageUrl()),
                        hasText(shot.getVideoUrl()),
                        hasText(shot.getAudioUrl()),
                        shot.getDuration());

                updateTask(task, "RUNNING",
                        5 + (70 * index / total),
                        String.format("正在合成第%d/%d个镜头...", index, total));

                // Download audio if available
                Path audioPath = null;
                if (hasText(shot.getAudioUrl())) {
                    audioPath = workDir.resolve("shot_" + shot.getShotNo() + ".mp3");
                    downloadFile(shot.getAudioUrl(), audioPath);
                }

                int duration = shot.getDuration() != null && shot.getDuration() > 0 ? shot.getDuration() : 5;
                Path shotVideo = workDir.resolve("shot_" + shot.getShotNo() + ".mp4");

                if (shouldUseDynamicVideo(shot) && hasText(shot.getVideoUrl())) {
                    Path sourceVideo = workDir.resolve("source_shot_" + shot.getShotNo() + ".mp4");
                    downloadFile(shot.getVideoUrl(), sourceVideo);
                    composeDynamicShot(sourceVideo, audioPath, shotVideo, duration);
                } else {
                    Path imagePath = workDir.resolve("shot_" + shot.getShotNo() + ".jpg");
                    downloadFile(shot.getImageUrl(), imagePath);
                    composeSingleShot(imagePath, audioPath, shotVideo, duration);
                }

                if (Files.exists(shotVideo) && Files.size(shotVideo) > 0) {
                    shotVideos.add(shotVideo);
                    log.debug("Shot video composed successfully: taskId={}, projectId={}, shotNo={}, output={}",
                            taskId, projectId, shot.getShotNo(), shotVideo);
                }
            }

            if (shotVideos.isEmpty()) {
                throw new BusinessException("没有成功生成任何镜头视频");
            }

            updateTask(task, "RUNNING", 80, "正在拼接最终视频...");

            // Step 2: Concatenate all shot videos
            String outputFilename = UUID.randomUUID().toString().replace("-", "") + ".mp4";
            Path finalVideo = videoDir.resolve(outputFilename);
            concatenateVideos(shotVideos, finalVideo, workDir);

            if (!Files.exists(finalVideo) || Files.size(finalVideo) == 0) {
                throw new BusinessException("视频拼接失败");
            }

            // Update project status
            String videoUrl = publicAssetStorageService.storeLocalFile(finalVideo, "videos", outputFilename, "video/mp4").publicUrl();
            if (videoUrl != null && !videoUrl.isBlank() && !videoUrl.startsWith(baseUrl)) {
                Files.deleteIfExists(finalVideo);
            }
            Project project = projectMapper.selectById(projectId);
            if (project != null) {
                project.setStatus("completed");
                projectMapper.updateById(project);
            }

            updateTask(task, "RUNNING", 95, "清理临时文件...");

            // Clean up work directory
            cleanupDirectory(workDir);

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage("视频合成完成！");
            task.setResult(videoUrl);
            taskRecordMapper.updateById(task);
            log.debug("Video composition complete: taskId={}, projectId={}, output={}, composedShots={}",
                    taskId, projectId, videoUrl, shotVideos.size());

        } catch (Exception e) {
            log.error("Video composition failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("视频合成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);

            // Update project status to failed
            try {
                Project project = projectMapper.selectById(projectId);
                if (project != null) {
                    project.setStatus("failed");
                    projectMapper.updateById(project);
                }
            } catch (Exception ex) {
                log.warn("Failed to update project status", ex);
            }
        }
    }

    @Async("aiTaskExecutor")
    public void generateDynamicVideosAsync(Long userId, Long projectId, List<Storyboard> shots, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;
        ArrayNode traceCalls = objectMapper.createArrayNode();
        int omittedTraceCalls = 0;
        try {
            int total = shots.size();
            int generated = 0;
            int failed = 0;
            int pending = 0;
            List<String> failedDetails = new ArrayList<>();

            log.debug("Dynamic video submission start: taskId={}, userId={}, projectId={}, shotCount={}",
                    taskId, userId, projectId, total);

            updateTask(task, "RUNNING", 5, "正在提交动态视频任务...");

            for (int index = 0; index < shots.size(); index++) {
                Storyboard shot = shots.get(index);
                if (!hasText(shot.getVideoPrompt()) && !hasText(shot.getDescription())) {
                    log.warn("Dynamic shot {} has no usable video prompt, skipped", shot.getShotNo());
                    failed++;
                    prepareShotForDynamicVideoTask(shot, taskId);
                    shot.setVideoTaskStatus("failed");
                    shot.setStatus("video_failed");
                    storyboardMapper.updateById(shot);
                    failedDetails.add(String.format("镜头%s: 缺少视频提示词", resolveShotLabel(shot)));
                    continue;
                }

                updateTask(task, "RUNNING",
                        10 + (40 * (index + 1) / total),
                        String.format("正在提交第%d/%d个动态镜头任务...", index + 1, total));

                try {
                    prepareShotForDynamicVideoTask(shot, taskId);
                    log.debug("Dynamic video request prepared: taskId={}, projectId={}, shotId={}, shotNo={}, promptLength={}, hasImage={}",
                            taskId,
                            projectId,
                            shot.getId(),
                            shot.getShotNo(),
                            hasText(shot.getVideoPrompt()) ? shot.getVideoPrompt().length() : (hasText(shot.getDescription()) ? shot.getDescription().length() : 0),
                            hasText(shot.getImageUrl()));
                    AiVideoGenerationService.VideoTaskSubmission submission = aiVideoGenerationService.submitVideoTask(userId, shot);
                    shot.setVideoTaskProvider(submission.provider());
                    if (hasText(submission.videoUrl())) {
                        shot.setVideoUrl(submission.videoUrl());
                        shot.setRenderMode("video");
                        shot.setVideoTaskStatus("success");
                        shot.setStatus("video_generated");
                        generated++;
                        log.debug("Dynamic video generated directly: taskId={}, projectId={}, shotId={}, shotNo={}, videoUrl={}",
                                taskId, projectId, shot.getId(), shot.getShotNo(), submission.videoUrl());
                    } else {
                        shot.setVideoTaskId(submission.taskId());
                        shot.setVideoTaskStatusUrl(submission.statusUrl());
                        shot.setVideoTaskStatus("submitted");
                        shot.setStatus("video_submitted");
                        pending++;
                        log.debug("Dynamic video task accepted: taskId={}, projectId={}, shotId={}, shotNo={}, vendorTaskId={}, statusUrl={}",
                                taskId, projectId, shot.getId(), shot.getShotNo(), submission.taskId(), submission.statusUrl());
                    }
                    storyboardMapper.updateById(shot);
                } catch (Exception e) {
                    failed++;
                    shot.setVideoTaskStatus("failed");
                    shot.setStatus("video_failed");
                    storyboardMapper.updateById(shot);
                    String shotLabel = resolveShotLabel(shot);
                    String errorMessage = hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
                    failedDetails.add(String.format("镜头%s: %s", shotLabel, errorMessage));
                    log.warn("Failed to generate dynamic video for shot {}", shotLabel, e);
                } finally {
                    omittedTraceCalls = appendTraceCalls(traceCalls, shot, AiTraceContext.drain(), omittedTraceCalls);
                }
            }

            Map<String, ?> summary = buildDynamicVideoSummary(taskId, total, generated, failed, pending);
            if (pending > 0) {
                task.setStatus("RUNNING");
                task.setProgress(calculateDynamicVideoProgress(total, generated, failed, pending));
                task.setMessage(buildDynamicVideoRunningMessage(generated, failed, pending));
                task.setResult(mergeTaskTraceResult(task.getResult(), "video", projectId, traceCalls, omittedTraceCalls, summary));
                taskRecordMapper.updateById(task);
                scheduleDynamicVideoPoll(userId, projectId, taskId, DYNAMIC_VIDEO_POLL_DELAY_SECONDS);
                return;
            }

            finishDynamicVideoTask(task, projectId, total, generated, failed, pending, failedDetails, traceCalls, omittedTraceCalls);
            log.debug("Dynamic video submission complete without pending poll: taskId={}, projectId={}, total={}, generated={}, failed={}",
                    taskId, projectId, total, generated, failed);
        } catch (Exception e) {
            log.error("Dynamic video generation failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("动态镜头生成失败: " + e.getMessage());
            task.setResult(mergeTaskTraceResult(task.getResult(), "video", projectId, traceCalls, omittedTraceCalls,
                    Map.of("error", e.getMessage())));
            taskRecordMapper.updateById(task);
        }
    }

    @Async("aiTaskExecutor")
    public void pollDynamicVideoTasksAsync(Long userId, Long projectId, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null || "SUCCESS".equalsIgnoreCase(task.getStatus()) || "FAILED".equalsIgnoreCase(task.getStatus())) {
            return;
        }

        List<Storyboard> shots = storyboardMapper.selectList(
                new LambdaQueryWrapper<Storyboard>()
                        .eq(Storyboard::getProjectId, projectId)
                        .eq(Storyboard::getVideoTaskRecordId, taskId)
                        .orderByAsc(Storyboard::getEpisodeNo)
                        .orderByAsc(Storyboard::getShotNo)
                        .orderByAsc(Storyboard::getId));
        if (shots.isEmpty()) {
            log.warn("No storyboard shots found for dynamic video task {}, skip polling", taskId);
            return;
        }

        ArrayNode traceCalls = objectMapper.createArrayNode();
        int omittedTraceCalls = 0;
        List<String> failedDetails = new ArrayList<>();
        boolean timedOut = isDynamicVideoTaskTimedOut(task);

        try {
            for (Storyboard shot : shots) {
                if (!isPendingDynamicVideoShot(shot)) {
                    continue;
                }

                if (timedOut) {
                    markDynamicVideoShotFailed(shot, "动态视频任务轮询超时，已等待至少10分钟", failedDetails);
                    continue;
                }

                try {
                    AiVideoGenerationService.VideoTaskQueryResult result = aiVideoGenerationService.querySubmittedVideoTask(userId, shot);
                    if (hasText(result.videoUrl()) && (isSuccessVideoTaskStatus(result.status()) || !hasText(result.status()))) {
                        shot.setVideoUrl(result.videoUrl());
                        shot.setRenderMode("video");
                        shot.setVideoTaskStatus(hasText(result.status()) ? result.status() : "success");
                        shot.setStatus("video_generated");
                    } else if (isFailureVideoTaskStatus(result.status())) {
                        markDynamicVideoShotFailed(shot,
                                hasText(result.errorMessage()) ? result.errorMessage() : "视频生成任务失败",
                                failedDetails);
                    } else {
                        shot.setVideoTaskStatus(hasText(result.status()) ? result.status() : "running");
                        shot.setStatus("video_polling");
                        storyboardMapper.updateById(shot);
                    }
                    if ("video_generated".equals(shot.getStatus())) {
                        storyboardMapper.updateById(shot);
                    }
                } catch (Exception e) {
                    markDynamicVideoShotFailed(shot,
                            hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(),
                            failedDetails);
                } finally {
                    omittedTraceCalls = appendTraceCalls(traceCalls, shot, AiTraceContext.drain(), omittedTraceCalls);
                }
            }

            int total = shots.size();
            int generated = (int) shots.stream().filter(shot -> "video_generated".equalsIgnoreCase(shot.getStatus())).count();
            int failed = (int) shots.stream().filter(shot -> "video_failed".equalsIgnoreCase(shot.getStatus())).count();
            int pending = (int) shots.stream().filter(this::isPendingDynamicVideoShot).count();
            Map<String, ?> summary = buildDynamicVideoSummary(taskId, total, generated, failed, pending);

            if (pending > 0 && !timedOut) {
                task.setStatus("RUNNING");
                task.setProgress(calculateDynamicVideoProgress(total, generated, failed, pending));
                task.setMessage(buildDynamicVideoRunningMessage(generated, failed, pending));
                task.setResult(mergeTaskTraceResult(task.getResult(), "video", projectId, traceCalls, omittedTraceCalls, summary));
                taskRecordMapper.updateById(task);
                scheduleDynamicVideoPoll(userId, projectId, taskId, DYNAMIC_VIDEO_POLL_DELAY_SECONDS);
                return;
            }

            finishDynamicVideoTask(task, projectId, total, generated, failed, pending, failedDetails, traceCalls, omittedTraceCalls);
        } catch (Exception e) {
            log.error("Dynamic video polling failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("动态视频轮询失败: " + e.getMessage());
            task.setResult(mergeTaskTraceResult(task.getResult(), "video", projectId, traceCalls, omittedTraceCalls,
                    Map.of("error", e.getMessage())));
            taskRecordMapper.updateById(task);
        }
    }

    /**
     * Compose a single shot video from image + optional audio using FFmpeg.
     * Creates a video with Ken Burns zoom effect.
     * Output format: 1080x1920 (9:16 vertical), 25fps, H.264+AAC
     */
    private void composeSingleShot(Path imagePath, Path audioPath, Path outputPath, int duration) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y"); // overwrite

        // Input image
        cmd.add("-loop"); cmd.add("1");
        cmd.add("-i"); cmd.add(imagePath.toAbsolutePath().toString());

        // Input audio (if available)
        if (audioPath != null && Files.exists(audioPath)) {
            cmd.add("-i"); cmd.add(audioPath.toAbsolutePath().toString());
        }

        // Video filter: scale to 1080x1920 (9:16) with slow zoom (Ken Burns effect)
        String videoFilter = buildVideoFilter(duration);
        cmd.add("-vf");
        cmd.add(videoFilter);

        // Video codec
        cmd.add("-c:v"); cmd.add("libx264");
        cmd.add("-preset"); cmd.add("fast");
        cmd.add("-pix_fmt"); cmd.add("yuv420p");
        cmd.add("-r"); cmd.add(String.valueOf(FRAME_RATE));

        // Audio codec
        if (audioPath != null && Files.exists(audioPath)) {
            cmd.add("-c:a"); cmd.add("aac");
            cmd.add("-b:a"); cmd.add("128k");
            cmd.add("-shortest");
        } else {
            // Generate silent audio
            cmd.add("-f"); cmd.add("lavfi");
            cmd.add("-i"); cmd.add("anullsrc=r=44100:cl=stereo");
            cmd.add("-c:a"); cmd.add("aac");
            cmd.add("-shortest");
        }

        cmd.add("-t"); cmd.add(String.valueOf(duration));
        cmd.add(outputPath.toAbsolutePath().toString());

        executeFFmpeg(cmd);
    }

    private void composeDynamicShot(Path videoPath, Path audioPath, Path outputPath, int duration) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");

        cmd.add("-i");
        cmd.add(videoPath.toAbsolutePath().toString());

        if (audioPath != null && Files.exists(audioPath)) {
            cmd.add("-i");
            cmd.add(audioPath.toAbsolutePath().toString());
        } else {
            cmd.add("-f");
            cmd.add("lavfi");
            cmd.add("-i");
            cmd.add("anullsrc=r=44100:cl=stereo");
        }

        cmd.add("-vf");
        cmd.add(String.format("scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2",
                VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_WIDTH, VIDEO_HEIGHT));

        cmd.add("-c:v"); cmd.add("libx264");
        cmd.add("-preset"); cmd.add("fast");
        cmd.add("-pix_fmt"); cmd.add("yuv420p");
        cmd.add("-r"); cmd.add(String.valueOf(FRAME_RATE));
        cmd.add("-c:a"); cmd.add("aac");
        cmd.add("-b:a"); cmd.add("128k");
        cmd.add("-shortest");
        cmd.add("-t"); cmd.add(String.valueOf(duration));
        cmd.add(outputPath.toAbsolutePath().toString());

        executeFFmpeg(cmd);
    }

    private void renderDynamicShotVideo(Path imagePath, Path outputPath, int duration, String motionLevel) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-loop"); cmd.add("1");
        cmd.add("-i"); cmd.add(imagePath.toAbsolutePath().toString());
        cmd.add("-vf"); cmd.add(buildDynamicVideoFilter(duration, motionLevel));
        cmd.add("-c:v"); cmd.add("libx264");
        cmd.add("-preset"); cmd.add("fast");
        cmd.add("-pix_fmt"); cmd.add("yuv420p");
        cmd.add("-r"); cmd.add(String.valueOf(FRAME_RATE));
        cmd.add("-t"); cmd.add(String.valueOf(duration));
        cmd.add("-an");
        cmd.add(outputPath.toAbsolutePath().toString());
        executeFFmpeg(cmd);
    }

    /** Output video width (vertical 9:16) */
    private static final int VIDEO_WIDTH = 1080;
    /** Output video height (vertical 9:16) */
    private static final int VIDEO_HEIGHT = 1920;
    /** Output video frame rate */
    private static final int FRAME_RATE = 25;

    /**
     * Build FFmpeg video filter for Ken Burns zoom effect on a still image.
     */
    private String buildVideoFilter(int durationSeconds) {
        int totalFrames = durationSeconds * FRAME_RATE;
        return String.format(
                "scale=%d:%d:force_original_aspect_ratio=decrease," +
                "pad=%d:%d:(ow-iw)/2:(oh-ih)/2," +
                "zoompan=z='min(zoom+0.001,1.3)':d=%d:s=%dx%d:fps=%d",
                VIDEO_WIDTH, VIDEO_HEIGHT,
                VIDEO_WIDTH, VIDEO_HEIGHT,
                totalFrames, VIDEO_WIDTH, VIDEO_HEIGHT, FRAME_RATE
        );
    }

    private String buildDynamicVideoFilter(int durationSeconds, String motionLevel) {
        int totalFrames = durationSeconds * FRAME_RATE;
        double zoomStep;
        double zoomMax;

        switch (motionLevel == null ? "low" : motionLevel.toLowerCase()) {
            case "high" -> {
                zoomStep = 0.0018;
                zoomMax = 1.34;
            }
            case "medium" -> {
                zoomStep = 0.0012;
                zoomMax = 1.24;
            }
            default -> {
                zoomStep = 0.0008;
                zoomMax = 1.16;
            }
        }

        return String.format(
                "scale=%d:%d:force_original_aspect_ratio=decrease," +
                "pad=%d:%d:(ow-iw)/2:(oh-ih)/2," +
                "zoompan=z='min(zoom+%.4f,%.2f)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=%d:s=%dx%d:fps=%d",
                VIDEO_WIDTH, VIDEO_HEIGHT,
                VIDEO_WIDTH, VIDEO_HEIGHT,
                zoomStep, zoomMax,
                totalFrames, VIDEO_WIDTH, VIDEO_HEIGHT, FRAME_RATE
        );
    }

    /**
     * Concatenate multiple video segments into a final video using FFmpeg concat demuxer.
     */
    private void concatenateVideos(List<Path> videos, Path outputPath, Path workDir) throws IOException, InterruptedException {
        // Create concat file list with properly escaped paths
        Path concatFile = workDir.resolve("concat.txt");
        StringBuilder sb = new StringBuilder();
        for (Path video : videos) {
            // Escape single quotes in file paths for FFmpeg concat format
            String escapedPath = video.toAbsolutePath().toString().replace("'", "'\\''");
            sb.append("file '").append(escapedPath).append("'\n");
        }
        Files.writeString(concatFile, sb.toString());

        List<String> cmd = List.of(
                ffmpegPath, "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", concatFile.toAbsolutePath().toString(),
                "-c:v", "libx264",
                "-preset", "fast",
                "-c:a", "aac",
                "-b:a", "128k",
                "-movflags", "+faststart",
                outputPath.toAbsolutePath().toString()
        );

        executeFFmpeg(cmd);
    }

    private void executeFFmpeg(List<String> cmd) throws IOException, InterruptedException {
        String executable = resolveFfmpegExecutable();
        cmd.set(0, executable);
        log.debug("FFmpeg command: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException(buildFfmpegStartFailureMessage(executable), e);
        }

        // Read output to prevent blocking
        List<String> outputLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.trace("FFmpeg: {}", line);
                if (outputLines.size() >= 40) {
                    outputLines.remove(0);
                }
                outputLines.add(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String tail = outputLines.isEmpty()
                    ? "<no ffmpeg output captured>"
                    : String.join(" | ", outputLines);
            throw new RuntimeException("FFmpeg exited with code " + exitCode
                    + ", command=" + String.join(" ", cmd)
                    + ", outputTail=" + tail);
        }
    }

    private String resolveFfmpegExecutable() {
        if (hasText(resolvedFfmpegExecutable)) {
            return resolvedFfmpegExecutable;
        }

        String configured = normalizeExecutableConfig(ffmpegPath);
        Path configuredPath = toPath(configured);
        if (configuredPath != null && Files.exists(configuredPath)) {
            resolvedFfmpegExecutable = configuredPath.toAbsolutePath().toString();
            log.info("FFmpeg executable resolved from configured path: {}", resolvedFfmpegExecutable);
            return resolvedFfmpegExecutable;
        }

        if (isWindows() && !configured.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            Path configuredExePath = toPath(configured + ".exe");
            if (configuredExePath != null && Files.exists(configuredExePath)) {
                resolvedFfmpegExecutable = configuredExePath.toAbsolutePath().toString();
                log.info("FFmpeg executable resolved from configured path with .exe: {}", resolvedFfmpegExecutable);
                return resolvedFfmpegExecutable;
            }
        }

        String foundOnPath = findOnSystemPath(configured);
        if (hasText(foundOnPath)) {
            resolvedFfmpegExecutable = foundOnPath;
            log.info("FFmpeg executable resolved from PATH: {}", resolvedFfmpegExecutable);
            return resolvedFfmpegExecutable;
        }

        if (isWindows()) {
            String foundExeOnPath = findOnSystemPath(configured + ".exe");
            if (hasText(foundExeOnPath)) {
                resolvedFfmpegExecutable = foundExeOnPath;
                log.info("FFmpeg executable resolved from PATH with .exe: {}", resolvedFfmpegExecutable);
                return resolvedFfmpegExecutable;
            }
        }

        // Fallback to configured value; startup error will include detailed diagnostics.
        resolvedFfmpegExecutable = configured;
        return resolvedFfmpegExecutable;
    }

    private String findOnSystemPath(String executableName) {
        String pathEnv = System.getenv("PATH");
        if (!hasText(pathEnv)) {
            return null;
        }
        String[] entries = pathEnv.split(isWindows() ? ";" : ":");
        for (String entry : entries) {
            if (!hasText(entry)) {
                continue;
            }
            try {
                Path candidate = Paths.get(entry.trim(), executableName);
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            } catch (Exception ignored) {
                // Skip invalid PATH entries or executable names and continue scanning.
            }
        }
        return null;
    }

    private String normalizeExecutableConfig(String configuredValue) {
        String value = hasText(configuredValue) ? configuredValue.trim() : "ffmpeg";
        while ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        // Handle malformed values such as "\ffmpeg or quoted strings copied from shell commands.
        value = value.replace("\"", "").trim();
        if (isWindows() && value.startsWith("\\") && !value.startsWith("\\\\") && !value.contains(":")) {
            value = value.substring(1);
        }
        return hasText(value) ? value : "ffmpeg";
    }

    private Path toPath(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Paths.get(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildFfmpegStartFailureMessage(String executable) {
        String pathEnv = System.getenv("PATH");
        int pathLength = pathEnv == null ? 0 : pathEnv.length();
        return "无法启动 FFmpeg，可执行文件未找到或不可执行。"
                + " configured='" + ffmpegPath + "'"
                + ", resolved='" + executable + "'"
                + ", os='" + System.getProperty("os.name") + "'"
                + ", PATH.length=" + pathLength
                + "。请在配置 niren.ffmpeg.path 中填写 ffmpeg.exe 绝对路径，或重启启动后端进程使最新 PATH 生效。";
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    }

    private void downloadFile(String url, Path target) throws IOException {
        if (url.startsWith(baseUrl)) {
            // Local file - resolve from upload path
            String relativePath = url.substring(baseUrl.length());
            if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
            Path localPath = Paths.get(uploadPath, relativePath);
            if (Files.exists(localPath)) {
                Files.copy(localPath, target);
                return;
            }
        }

        // Remote file - download via HTTP
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() != 200) {
                throw new IOException("Download failed with status " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private void prepareShotForDynamicVideoTask(Storyboard shot, Long taskId) {
        shot.setVideoUrl(null);
        shot.setVideoTaskId(null);
        shot.setVideoTaskStatusUrl(null);
        shot.setVideoTaskProvider(null);
        shot.setVideoTaskStatus(null);
        shot.setVideoTaskRecordId(taskId);
    }

    private void markDynamicVideoShotFailed(Storyboard shot, String reason, List<String> failedDetails) {
        shot.setVideoUrl(null);
        shot.setVideoTaskStatus("failed");
        shot.setStatus("video_failed");
        storyboardMapper.updateById(shot);
        if (failedDetails != null) {
            failedDetails.add(String.format("镜头%s: %s", resolveShotLabel(shot), reason));
        }
    }

    private String resolveShotLabel(Storyboard shot) {
        return shot.getShotNo() != null ? String.valueOf(shot.getShotNo()) : String.valueOf(shot.getId());
    }

    private void scheduleDynamicVideoPoll(Long userId, Long projectId, Long taskId, long delaySeconds) {
        Instant runAt = Instant.now().plusSeconds(Math.max(1L, delaySeconds));
        log.debug("Schedule dynamic video polling: taskId={}, projectId={}, runAt={}", taskId, projectId, runAt);
        aiPollScheduler.schedule(
                () -> selfProvider.getObject().pollDynamicVideoTasksAsync(userId, projectId, taskId),
                runAt);
    }

    private boolean isDynamicVideoTaskTimedOut(TaskRecord task) {
        LocalDateTime createTime = task.getCreateTime();
        return createTime != null && Duration.between(createTime, LocalDateTime.now()).compareTo(DYNAMIC_VIDEO_TASK_TIMEOUT) >= 0;
    }

    private boolean isPendingDynamicVideoShot(Storyboard shot) {
        if (shot == null || shot.getVideoTaskRecordId() == null) {
            return false;
        }
        if ("video_generated".equalsIgnoreCase(shot.getStatus()) || "video_failed".equalsIgnoreCase(shot.getStatus())) {
            return false;
        }
        return "video_submitted".equalsIgnoreCase(shot.getStatus())
                || "video_polling".equalsIgnoreCase(shot.getStatus())
                || isPendingVideoTaskStatus(shot.getVideoTaskStatus());
    }

    private boolean isSuccessVideoTaskStatus(String status) {
        if (!hasText(status)) {
            return false;
        }
        String normalized = status.trim().toLowerCase();
        return "success".equals(normalized)
                || "succeeded".equals(normalized)
                || "completed".equals(normalized)
                || "done".equals(normalized)
                || "finished".equals(normalized);
    }

    private boolean isFailureVideoTaskStatus(String status) {
        if (!hasText(status)) {
            return false;
        }
        String normalized = status.trim().toLowerCase();
        return "failed".equals(normalized)
                || "error".equals(normalized)
                || "cancelled".equals(normalized)
                || "canceled".equals(normalized)
                || "rejected".equals(normalized);
    }

    private boolean isPendingVideoTaskStatus(String status) {
        if (!hasText(status)) {
            return false;
        }
        String normalized = status.trim().toLowerCase();
        return "submitted".equals(normalized)
                || "pending".equals(normalized)
                || "queued".equals(normalized)
                || "running".equals(normalized)
                || "processing".equals(normalized)
                || "in_progress".equals(normalized);
    }

    private Map<String, ?> buildDynamicVideoSummary(Long taskId, int total, int generated, int failed, int pending) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("generated", generated);
        summary.put("failed", failed);
        summary.put("pending", pending);
        summary.put("asyncTasks", buildDynamicVideoTaskItems(taskId));
        return summary;
    }

    private List<Map<String, Object>> buildDynamicVideoTaskItems(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        List<Storyboard> taskShots = storyboardMapper.selectList(
                new LambdaQueryWrapper<Storyboard>()
                        .eq(Storyboard::getVideoTaskRecordId, taskId)
                        .orderByAsc(Storyboard::getEpisodeNo)
                        .orderByAsc(Storyboard::getShotNo)
                        .orderByAsc(Storyboard::getId));
        if (taskShots == null || taskShots.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Storyboard shot : taskShots) {
            if (!hasText(shot.getVideoTaskId()) && !hasText(shot.getVideoTaskStatusUrl())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("shotId", shot.getId());
            item.put("shotNo", shot.getShotNo());
            item.put("taskId", shot.getVideoTaskId());
            item.put("statusUrl", shot.getVideoTaskStatusUrl());
            item.put("provider", shot.getVideoTaskProvider());
            item.put("taskStatus", shot.getVideoTaskStatus());
            item.put("renderStatus", shot.getStatus());
            item.put("videoUrl", shot.getVideoUrl());
            items.add(item);
        }
        return items;
    }

    private int calculateDynamicVideoProgress(int total, int generated, int failed, int pending) {
        if (total <= 0) {
            return 0;
        }
        if (pending <= 0) {
            return 100;
        }
        int completed = generated + failed;
        return Math.max(10, Math.min(99, 50 + (45 * completed / total)));
    }

    private String buildDynamicVideoRunningMessage(int generated, int failed, int pending) {
        return String.format("动态镜头任务已提交，已完成%d个，失败%d个，剩余%d个轮询中", generated, failed, pending);
    }

    private void finishDynamicVideoTask(TaskRecord task,
                                        Long projectId,
                                        int total,
                                        int generated,
                                        int failed,
                                        int pending,
                                        List<String> failedDetails,
                                        ArrayNode traceCalls,
                                        int omittedTraceCalls) {
        task.setProgress(100);
        task.setResult(mergeTaskTraceResult(task.getResult(), "video", projectId, traceCalls, omittedTraceCalls,
            buildDynamicVideoSummary(task.getId(), total, generated, failed, pending)));
        String failureSummary = buildFailureReasonSummary(failedDetails, 3);
        if (generated == 0 && failed > 0 && pending == 0) {
            task.setStatus("FAILED");
            task.setMessage(hasText(failureSummary)
                    ? String.format("动态镜头生成失败，所选%d个镜头均未生成成功。%s", total, failureSummary)
                    : String.format("动态镜头生成失败，所选%d个镜头均未生成成功", total));
        } else {
            task.setStatus("SUCCESS");
            if (failed > 0) {
                task.setMessage(hasText(failureSummary)
                        ? String.format("动态镜头生成完成：成功%d个，失败%d个。%s", generated, failed, failureSummary)
                        : String.format("动态镜头生成完成：成功%d个，失败%d个", generated, failed));
            } else {
                task.setMessage(String.format("动态镜头生成完成，共生成%d个片段", generated));
            }
        }
        taskRecordMapper.updateById(task);
    }

    private String mergeTaskTraceResult(String existingResult,
                                        String mediaType,
                                        Long projectId,
                                        ArrayNode newCalls,
                                        int newOmittedTraceCalls,
                                        Map<String, ?> summary) {
        try {
            ArrayNode mergedCalls = objectMapper.createArrayNode();
            int omittedTraceCalls = Math.max(0, newOmittedTraceCalls);

            if (hasText(existingResult)) {
                JsonNode existingRoot = objectMapper.readTree(existingResult);
                omittedTraceCalls += existingRoot.path("omittedCalls").asInt(0);
                JsonNode existingCalls = existingRoot.path("calls");
                if (existingCalls.isArray()) {
                    for (JsonNode call : existingCalls) {
                        if (mergedCalls.size() >= MAX_TASK_TRACE_CALLS) {
                            omittedTraceCalls++;
                            continue;
                        }
                        mergedCalls.add(call);
                    }
                }
            }

            if (newCalls != null) {
                for (JsonNode call : newCalls) {
                    if (mergedCalls.size() >= MAX_TASK_TRACE_CALLS) {
                        omittedTraceCalls++;
                        continue;
                    }
                    mergedCalls.add(call);
                }
            }

            return buildTaskTraceResult(mediaType, projectId, mergedCalls, omittedTraceCalls, summary);
        } catch (Exception e) {
            log.warn("Failed to merge AI task trace result", e);
            return buildTaskTraceResult(mediaType, projectId,
                    newCalls != null ? newCalls : objectMapper.createArrayNode(),
                    newOmittedTraceCalls,
                    summary);
        }
    }

    private boolean shouldUseDynamicVideo(Storyboard shot) {
        return Boolean.TRUE.equals(shot.getDynamicSelected()) || "video".equalsIgnoreCase(shot.getRenderMode());
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private void cleanupDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.walk(dir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                log.warn("Failed to delete: {}", path);
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("Failed to clean up directory: {}", dir, e);
        }
    }

    private void updateTask(TaskRecord task, String status, int progress, String message) {
        log.debug("Task update: taskId={}, taskType={}, status={}, progress={}, message={}",
                task.getId(), task.getTaskType(), status, progress, message);
        task.setStatus(status);
        task.setProgress(progress);
        task.setMessage(message);
        taskRecordMapper.updateById(task);
    }

    private int appendTraceCalls(ArrayNode calls, Storyboard shot, List<AiCallTrace> traces, int omittedTraceCalls) {
        if (traces == null || traces.isEmpty()) {
            return omittedTraceCalls;
        }
        for (AiCallTrace trace : traces) {
            if (calls.size() >= MAX_TASK_TRACE_CALLS) {
                omittedTraceCalls++;
                continue;
            }
            ObjectNode node = objectMapper.valueToTree(trace);
            if (shot != null) {
                if (shot.getId() != null) {
                    node.put("shotId", shot.getId());
                }
                if (shot.getShotNo() != null) {
                    node.put("shotNo", shot.getShotNo());
                }
            }
            calls.add(node);
        }
        return omittedTraceCalls;
    }

    private String buildTaskTraceResult(String mediaType,
                                        Long projectId,
                                        ArrayNode calls,
                                        int omittedTraceCalls,
                                        java.util.Map<String, ?> summary) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("mediaType", mediaType);
            root.put("projectId", projectId);
            root.put("storedCalls", calls.size());
            root.put("omittedCalls", omittedTraceCalls);
            root.set("summary", objectMapper.valueToTree(summary));
            root.set("calls", calls);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to serialize AI task trace result", e);
            return null;
        }
    }

    private String buildFailureReasonSummary(List<String> failedDetails, int maxItems) {
        if (failedDetails == null || failedDetails.isEmpty()) {
            return "";
        }
        int limit = Math.max(1, maxItems);
        List<String> reasons = failedDetails.stream().limit(limit).toList();
        String summary = String.join("；", reasons);
        int remaining = failedDetails.size() - reasons.size();
        if (remaining > 0) {
            summary = summary + String.format("；另有%d个失败镜头", remaining);
        }
        return "失败原因：" + summary;
    }

    /**
     * Get the latest successful video compose task result for a project.
     */
        public TaskRecord getLatestVideoTask(Long projectId) {
        return taskRecordMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskRecord>()
                        .eq(TaskRecord::getProjectId, projectId)
                .eq(TaskRecord::getTaskType, "VIDEO_COMPOSE")
                .orderByDesc(TaskRecord::getCreateTime)
                .last("LIMIT 1"));
        }

        public TaskRecord getLatestPipelineTask(Long projectId) {
        return taskRecordMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getProjectId, projectId)
                .in(TaskRecord::getTaskType, "IMAGE_GEN", "DYNAMIC_VIDEO_GEN", "AUDIO_GEN", "VIDEO_COMPOSE")
                        .orderByDesc(TaskRecord::getCreateTime)
                        .last("LIMIT 1"));
    }

    /**
     * Get the physical path of the video file for download.
     */
    public Path getVideoFilePath(String videoUrl) {
        if (videoUrl == null || !videoUrl.startsWith(baseUrl)) return null;
        String relativePath = videoUrl.substring(baseUrl.length());
        if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
        return Paths.get(uploadPath, relativePath);
    }

    /**
     * Delete the video file and clear the task result URL after it has been exported.
     * Called after the video has been fully streamed to the client.
     * Returns true if the file was successfully deleted.
     */
    public boolean deleteVideoAndClearResult(Long taskId, Path videoPath) {
        boolean deleted = false;
        try {
            if (videoPath != null) {
                deleted = Files.deleteIfExists(videoPath);
            }
            if (deleted) {
                log.info("Deleted exported video file: {}", videoPath);
            }
        } catch (IOException e) {
            log.warn("Failed to delete video file after export: {}", videoPath, e);
        }
        try {
            TaskRecord task = taskRecordMapper.selectById(taskId);
            if (task != null) {
                task.setResult(null);
                task.setMessage(deleted
                        ? "成片已导出并已自动清理（节约存储空间）"
                        : "成片已导出（文件清理失败，请手动清理）");
                taskRecordMapper.updateById(task);
            }
        } catch (Exception e) {
            log.warn("Failed to clear task result after export for task {}", taskId, e);
        }
        return deleted;
    }
}
