package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.TtsProvider;
import com.niren.drama.ai.trace.AiCallTrace;
import com.niren.drama.ai.trace.AiTraceContext;
import com.niren.drama.entity.Project;
import com.niren.drama.common.DramaTextSanitizer;
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
import java.util.Comparator;
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
    private static final double DEFAULT_SHOT_DURATION_SECONDS = 5.0d;
    private static final double MIN_SHOT_DURATION_SECONDS = 2.5d;
    private static final double DEFAULT_BGM_FADE_OUT_SECONDS = 1.2d;
    private static final double MIN_VOICE_DRIVEN_PAUSE_SECONDS = 0.2d;
    private static final double MAX_VOICE_DRIVEN_PAUSE_SECONDS = 0.5d;
    /** Output video width (vertical 9:16) */
    private static final int VIDEO_WIDTH = 1080;
    /** Output video height (vertical 9:16) */
    private static final int VIDEO_HEIGHT = 1920;
    /** Output video frame rate */
    private static final int FRAME_RATE = 25;
    private static final List<String> DEFAULT_TRANSITIONS = List.of(
            "fade",
            "fadeblack",
            "fadewhite",
            "smoothleft",
            "smoothup",
            "slideright"
    );

    private final StoryboardService storyboardService;
    private final StoryboardMapper storyboardMapper;
    private final ProjectMapper projectMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final AiVideoGenerationService aiVideoGenerationService;
    private final AiProviderFactory aiProviderFactory;
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

    @Value("${niren.ffmpeg.ffprobe-path:}")
    private String ffprobePath;

    @Value("${niren.compose.transition.duration-seconds:0.45}")
    private double composeTransitionDuration;

    @Value("${niren.compose.audio.tail-padding-seconds:0.35}")
    private double composeAudioTailPadding;

    @Value("${niren.compose.subtitle.enabled:true}")
    private boolean composeSubtitleEnabled;

    @Value("${niren.compose.subtitle.font-path:}")
    private String composeSubtitleFontPath;

    @Value("${niren.compose.subtitle.font-size:54}")
    private int composeSubtitleFontSize;

    @Value("${niren.compose.subtitle.margin-bottom:160}")
    private int composeSubtitleMarginBottom;

    /** 字幕是否叠旁白；false=更像真人短剧（上屏以口播为主），旁白可走配音轨由业务侧单独处理 */
    @Value("${niren.compose.subtitle.include-narration:false}")
    private boolean composeSubtitleIncludeNarration;
    @Value("${niren.compose.subtitle.strip-speaker-prefix:true}")
    private boolean composeSubtitleStripSpeakerPrefix;

    @Value("${niren.compose.bgm.source:}")
    private String composeBgmSource;

    @Value("${niren.compose.bgm.volume:0.16}")
    private double composeBgmVolume;

    @Value("${niren.compose.transition-sfx.source:}")
    private String composeTransitionSfxSource;
    @Value("${niren.compose.transition-sfx.conflict-source:}")
    private String composeTransitionConflictSfxSource;
    @Value("${niren.compose.transition-sfx.lyrical-source:}")
    private String composeTransitionLyricalSfxSource;
    @Value("${niren.compose.transition-sfx.follow-emotion-enabled:true}")
    private boolean composeTransitionSfxFollowEmotion;

    @Value("${niren.compose.transition-sfx.volume:0.18}")
    private double composeTransitionSfxVolume;

    @Value("${niren.compose.max-shot-duration-seconds:5.0}")
    private double composeMaxShotDurationSeconds;

    @Value("${niren.compose.audio.between-shots-seconds:0.28}")
    private double composeAudioBetweenShotsSeconds;
    @Value("${niren.compose.sync.voice-driven-enabled:true}")
    private boolean composeVoiceDrivenSyncEnabled;
    @Value("${niren.compose.sync.pause-seconds:0.28}")
    private double composeSyncPauseSeconds;
    @Value("${niren.compose.sync.max-overrun-seconds:1.25}")
    private double composeSyncMaxOverrunSeconds;
    @Value("${niren.compose.audio.voice-loudnorm-enabled:true}")
    private boolean composeVoiceLoudnormEnabled;
    @Value("${niren.compose.audio.ducking-enabled:true}")
    private boolean composeAudioDuckingEnabled;
    @Value("${niren.compose.audio.ducking-threshold:0.035}")
    private double composeAudioDuckingThreshold;
    @Value("${niren.compose.audio.ducking-ratio:10.0}")
    private double composeAudioDuckingRatio;
    @Value("${niren.compose.audio.ducking-attack:15}")
    private int composeAudioDuckingAttack;
    @Value("${niren.compose.audio.ducking-release:280}")
    private int composeAudioDuckingRelease;

    @Value("${niren.compose.transition.conflict-seconds:0.2}")
    private double composeTransitionConflictSeconds;

    @Value("${niren.compose.transition.lyrical-seconds:0.8}")
    private double composeTransitionLyricalSeconds;

    @Value("${niren.compose.ambient.source:}")
    private String composeAmbientSource;

    @Value("${niren.compose.ambient.volume:0.1}")
    private double composeAmbientVolume;
    @Value("${niren.compose.rhythm.auto-micro-motion-enabled:true}")
    private boolean composeAutoMicroMotionEnabled;
    @Value("${niren.compose.rhythm.max-static-seconds:10}")
    private double composeRhythmMaxStaticSeconds;
    @Value("${niren.compose.rhythm.max-static-shots:3}")
    private int composeRhythmMaxStaticShots;
    @Value("${niren.compose.export.profile:publish}")
    private String composeExportProfile;
    @Value("${niren.compose.export.preview.preset:veryfast}")
    private String composePreviewPreset;
    @Value("${niren.compose.export.preview.crf:24}")
    private int composePreviewCrf;
    @Value("${niren.compose.export.preview.audio-bitrate:128k}")
    private String composePreviewAudioBitrate;
    @Value("${niren.compose.export.publish.preset:slow}")
    private String composePublishPreset;
    @Value("${niren.compose.export.publish.crf:19}")
    private int composePublishCrf;
    @Value("${niren.compose.export.publish.audio-bitrate:192k}")
    private String composePublishAudioBitrate;
    @Value("${niren.dynamic-video.timeout-minutes:20}")
    private long dynamicVideoTimeoutMinutes;
    @Value("${niren.dynamic-video.poll.base-seconds:5}")
    private long dynamicVideoPollBaseSeconds;
    @Value("${niren.dynamic-video.poll.max-seconds:20}")
    private long dynamicVideoPollMaxSeconds;
    @Value("${niren.compose.segmented.enabled:true}")
    private boolean composeSegmentedEnabled;
    @Value("${niren.compose.segmented.max-shots-per-segment:24}")
    private int composeMaxShotsPerSegment;
    @Value("${niren.compose.narration.enabled:true}")
    private boolean composeNarrationEnabled;
    @Value("${niren.compose.narration.volume:0.22}")
    private double composeNarrationVolume;
    @Value("${niren.compose.narration.ducking-when-dialogue:true}")
    private boolean composeNarrationDuckingWhenDialogue;
    @Value("${niren.compose.narration.max-segment-seconds:3.5}")
    private double composeNarrationMaxSegmentSeconds;
    @Value("${niren.compose.narration.pause-on-conflict:true}")
    private boolean composeNarrationPauseOnConflict;
    @Value("${niren.compose.mix.dialogue-priority:true}")
    private boolean composeDialoguePriority;
    @Value("${niren.compose.mix.narration-recover-ms:280}")
    private int composeNarrationRecoverMs;
    @Value("${niren.compose.mix.dialogue-threshold:0.035}")
    private double composeDialogueThreshold;
    @Value("${niren.dynamic-priority.target-dynamic-ratio:0.99}")
    private double targetDynamicRatio;
    @Value("${niren.dynamic-priority.force-dynamic-by-default:true}")
    private boolean forceDynamicByDefault;
    @Value("${niren.dynamic-priority.quality-max-retry:1}")
    private int dynamicQualityMaxRetry;

    private volatile String resolvedFfmpegExecutable;
    private volatile String resolvedFfprobeExecutable;

    private enum TransitionMood {
        DEFAULT,
        CONFLICT,
        LYRICAL
    }

    private record ShotRenderPlan(double contentDuration,
                                  double clipDuration,
                                  double subtitleVisibleDuration,
                                  String subtitleText) {}

    private record ShotSegment(Path videoPath, double clipDuration, Storyboard shot) {}
    private record NarrationSegment(long startMs, String text, double maxSeconds) {}

    /**
     * Start the video composition process for a project.
     */
    public TaskRecord startCompose(Long userId, Long projectId, java.util.List<Long> shotIds) {
        List<Storyboard> shots = storyboardService.listByProject(projectId);
        if (shotIds != null && !shotIds.isEmpty()) {
            shots = shots.stream().filter(s -> shotIds.contains(s.getId())).collect(java.util.stream.Collectors.toList());
        }
        shots = shots.stream()
                .sorted(Comparator.comparing(Storyboard::getEpisodeNo, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Storyboard::getShotNo, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Storyboard::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        if (shots.isEmpty()) {
            throw new BusinessException("项目下没有分镜数据，请先生成分镜");
        }

        long missingVideoCount = shots.stream().filter(s -> !hasText(s.getVideoUrl())).count();
        if (missingVideoCount > 0) {
            throw new BusinessException("分镜视频尚未生成完成，请先生成分镜视频（缺少 " + missingVideoCount + " 个镜头）");
        }

        log.debug("创建视频合成任务: userId={}, projectId={}, shotCount={}, filteredByIds={}",
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
                .sorted(Comparator.comparing(Storyboard::getEpisodeNo, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Storyboard::getShotNo, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Storyboard::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();

        if (selectedShots.isEmpty()) {
            throw new BusinessException("项目下没有分镜数据，请先生成分镜");
        }

        boolean allReadyForDynamic = selectedShots.stream().allMatch(s -> hasText(s.getVideoPrompt()) || hasText(s.getDescription()));
        if (!allReadyForDynamic) {
            throw new BusinessException("仍有分镜缺少视频提示词，请先完成分镜生成或补全镜头描述");
        }

        log.debug("创建分镜视频任务: userId={}, projectId={}, selectedShots={}, filteredByIds={}",
            userId, projectId, selectedShots.size(), shotIds != null && !shotIds.isEmpty());

        TaskRecord task = new TaskRecord();
        task.setProjectId(projectId);
        task.setUserId(userId);
        task.setTaskType("DYNAMIC_VIDEO_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("分镜视频生成任务已提交...");
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
                log.info("恢复动态视频轮询任务: taskId={}, projectId={}, pendingShots={}",
                        task.getId(), task.getProjectId(), pendingCount);
                scheduleDynamicVideoPoll(task.getUserId(), task.getProjectId(), task.getId(), dynamicVideoPollBaseSeconds);
            }
        }
    }

    @Async("aiTaskExecutor")
    public void composeAsync(Long userId, Long projectId, List<Storyboard> shots, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;

        Path workDir = null;
        try {
            log.debug("视频合成开始: taskId={}, userId={}, projectId={}, shotCount={}",
                    taskId, userId, projectId, shots.size());
            // Create working directory
            workDir = Paths.get(uploadPath, "compose", projectId.toString());
            Files.createDirectories(workDir);

            Path videoDir = Paths.get(uploadPath, "videos");
            Files.createDirectories(videoDir);

            updateTask(task, "RUNNING", 5, "准备合成素材...");

            List<Storyboard> renderableShots = shots.stream()
                    .filter(this::hasRenderableMedia)
                    .toList();
            if (renderableShots.isEmpty()) {
                throw new BusinessException("没有可用于合成的分镜视频，请先生成分镜视频");
            }

            Path bgmPath = prepareComposeAsset(composeBgmSource,
                    workDir.resolve("compose_bgm" + resolveAssetExtension(composeBgmSource, ".mp3")));
            Path transitionSfxPath = prepareComposeAsset(composeTransitionSfxSource,
                    workDir.resolve("transition_sfx" + resolveAssetExtension(composeTransitionSfxSource, ".mp3")));
            Path transitionConflictSfxPath = prepareComposeAsset(composeTransitionConflictSfxSource,
                    workDir.resolve("transition_sfx_conflict" + resolveAssetExtension(composeTransitionConflictSfxSource, ".mp3")));
            Path transitionLyricalSfxPath = prepareComposeAsset(composeTransitionLyricalSfxSource,
                    workDir.resolve("transition_sfx_lyrical" + resolveAssetExtension(composeTransitionLyricalSfxSource, ".mp3")));
            Path ambientPath = prepareComposeAsset(composeAmbientSource,
                    workDir.resolve("compose_ambient" + resolveAssetExtension(composeAmbientSource, ".mp3")));

            // Step 1: Download images to local files
            List<ShotSegment> shotVideos = new ArrayList<>();
            int total = renderableShots.size();
            int consecutiveStaticShots = 0;
            double consecutiveStaticSeconds = 0d;

            for (int index = 0; index < renderableShots.size(); index++) {
                Storyboard shot = renderableShots.get(index);
                boolean hasNext = index < renderableShots.size() - 1;
                Storyboard nextShot = hasNext ? renderableShots.get(index + 1) : null;
                double transitionToNext = hasNext ? resolvePairTransitionDuration(shot, nextShot) : 0d;

                log.debug("镜头合成开始: taskId={}, projectId={}, shotId={}, shotNo={}, renderMode={}, hasImage={}, hasVideo={}, hasAudio={}, duration={}",
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
                        5 + (70 * (index + 1) / total),
                        String.format("正在合成第%d/%d个镜头...", index + 1, total));

                // Download audio if available
                Path audioPath = null;
                if (hasText(shot.getAudioUrl())) {
                    audioPath = workDir.resolve("shot_" + shot.getShotNo() + ".mp3");
                    downloadFile(shot.getAudioUrl(), audioPath);
                }

                double audioDuration = measureMediaDurationSeconds(audioPath);
                ShotRenderPlan renderPlan = buildShotRenderPlan(
                        shot,
                        audioDuration,
                        hasNext ? transitionToNext : 0d);
                Path shotVideo = workDir.resolve("shot_" + shot.getShotNo() + ".mp4");
                boolean dynamicSource = hasText(shot.getVideoUrl());
                boolean applyMicroMotion = shouldApplyMicroMotion(shot, dynamicSource, consecutiveStaticShots, consecutiveStaticSeconds);

                if (dynamicSource) {
                    Path sourceVideo = workDir.resolve("source_shot_" + shot.getShotNo() + ".mp4");
                    downloadFile(shot.getVideoUrl(), sourceVideo);
                    composeDynamicShot(sourceVideo, audioPath, shotVideo, renderPlan, shot);
                    int retry = 0;
                    while (retry < Math.max(0, dynamicQualityMaxRetry) && !isDynamicClipQualityAcceptable(shotVideo)) {
                        retry++;
                        log.debug("动态镜头质量未达阈值，触发重编码: taskId={}, shotNo={}, retry={}", taskId, shot.getShotNo(), retry);
                        composeDynamicShot(sourceVideo, audioPath, shotVideo, renderPlan, shot);
                    }
                    consecutiveStaticShots = 0;
                    consecutiveStaticSeconds = 0d;
                } else {
                    Path imagePath = workDir.resolve("shot_" + shot.getShotNo() + ".jpg");
                    downloadFile(shot.getImageUrl(), imagePath);
                    composeSingleShot(imagePath, audioPath, shotVideo, renderPlan, shot, applyMicroMotion);
                    consecutiveStaticShots++;
                    consecutiveStaticSeconds += renderPlan.contentDuration();
                }

                if (Files.exists(shotVideo) && Files.size(shotVideo) > 0) {
                    shotVideos.add(new ShotSegment(shotVideo, renderPlan.clipDuration(), shot));
                    log.debug("镜头合成成功: taskId={}, projectId={}, shotNo={}, output={}",
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
            Path narrationTrack = generateGlobalNarrationTrack(userId, shotVideos, workDir);
            concatenateVideosSmart(shotVideos, finalVideo, workDir, bgmPath, transitionSfxPath, transitionConflictSfxPath, transitionLyricalSfxPath, ambientPath, narrationTrack);

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
            task.setResult(buildComposeTaskResult(videoUrl, shotVideos, narrationTrack));
            taskRecordMapper.updateById(task);
            log.debug("视频合成完成: taskId={}, projectId={}, output={}, composedShots={}",
                    taskId, projectId, videoUrl, shotVideos.size());

        } catch (Exception e) {
            log.error("视频合成失败: taskId={}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("视频合成失败: " + buildFailureReason(e));
            taskRecordMapper.updateById(task);

            // Update project status to failed
            try {
                Project project = projectMapper.selectById(projectId);
                if (project != null) {
                    project.setStatus("failed");
                    projectMapper.updateById(project);
                }
            } catch (Exception ex) {
                log.warn("更新项目状态失败", ex);
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

            log.debug("动态视频提交开始: taskId={}, userId={}, projectId={}, shotCount={}",
                    taskId, userId, projectId, total);

            updateTask(task, "RUNNING", 5, "正在提交动态视频任务...");

            for (int index = 0; index < shots.size(); index++) {
                Storyboard shot = shots.get(index);
                if (!hasText(shot.getVideoPrompt()) && !hasText(shot.getDescription())) {
                    log.warn("动态镜头缺少可用提示词，已跳过: shotNo={}", shot.getShotNo());
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
                    log.debug("动态视频请求已准备: taskId={}, projectId={}, shotId={}, shotNo={}, promptLength={}, hasImage={}",
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
                        log.debug("动态视频直接返回成功: taskId={}, projectId={}, shotId={}, shotNo={}, videoUrl={}",
                                taskId, projectId, shot.getId(), shot.getShotNo(), submission.videoUrl());
                    } else {
                        shot.setVideoTaskId(submission.taskId());
                        shot.setVideoTaskStatusUrl(submission.statusUrl());
                        shot.setVideoTaskStatus("submitted");
                        shot.setStatus("video_submitted");
                        pending++;
                        log.debug("动态视频任务已受理: taskId={}, projectId={}, shotId={}, shotNo={}, vendorTaskId={}, statusUrl={}",
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
                    log.warn("动态视频生成失败: shotLabel={}", shotLabel, e);
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
                scheduleDynamicVideoPoll(userId, projectId, taskId, resolveDynamicPollDelaySeconds(task));
                return;
            }

            finishDynamicVideoTask(task, projectId, total, generated, failed, pending, failedDetails, traceCalls, omittedTraceCalls);
            log.debug("动态视频提交完成(无需轮询): taskId={}, projectId={}, total={}, generated={}, failed={}",
                    taskId, projectId, total, generated, failed);
        } catch (Exception e) {
            log.error("动态视频生成失败: taskId={}", taskId, e);
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
            log.warn("动态视频轮询未找到分镜，任务将标记失败: taskId={}, projectId={}", taskId, projectId);
            task.setStatus("FAILED");
            task.setMessage("动态视频轮询失败: 未找到关联分镜数据");
            taskRecordMapper.updateById(task);
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
                scheduleDynamicVideoPoll(userId, projectId, taskId, resolveDynamicPollDelaySeconds(task));
                return;
            }

            finishDynamicVideoTask(task, projectId, total, generated, failed, pending, failedDetails, traceCalls, omittedTraceCalls);
        } catch (Exception e) {
            log.error("动态视频轮询失败: taskId={}", taskId, e);
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
    private void composeSingleShot(Path imagePath,
                                   Path audioPath,
                                   Path outputPath,
                                   ShotRenderPlan renderPlan,
                                   Storyboard shot,
                                   boolean microMotionEnabled) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y"); // overwrite

        // Input image
        cmd.add("-loop"); cmd.add("1");
        cmd.add("-framerate"); cmd.add(String.valueOf(FRAME_RATE));
        cmd.add("-i"); cmd.add(imagePath.toAbsolutePath().toString());

        // Input audio (if available)
        if (audioPath != null && Files.exists(audioPath)) {
            cmd.add("-i"); cmd.add(audioPath.toAbsolutePath().toString());
        }

        // Audio input (if missing, synthesize silent input).
        if (audioPath == null || !Files.exists(audioPath)) {
            cmd.add("-f"); cmd.add("lavfi");
            cmd.add("-i"); cmd.add("anullsrc=r=44100:cl=stereo");
        }

        cmd.add("-map"); cmd.add("0:v:0");
        cmd.add("-map"); cmd.add("1:a:0");

        String videoFilter = buildStillShotFilter(shot, renderPlan, microMotionEnabled);
        cmd.add("-vf");
        cmd.add(videoFilter);
        cmd.add("-af");
        cmd.add(buildShotAudioFilter(renderPlan.clipDuration()));

        // Video codec
        cmd.add("-c:v"); cmd.add("libx264");
        cmd.add("-preset"); cmd.add("medium");
        cmd.add("-crf"); cmd.add("20");
        cmd.add("-pix_fmt"); cmd.add("yuv420p");
        cmd.add("-r"); cmd.add(String.valueOf(FRAME_RATE));

        cmd.add("-c:a"); cmd.add("aac");
        cmd.add("-b:a"); cmd.add("160k");
        cmd.add("-ar"); cmd.add("44100");
        cmd.add("-ac"); cmd.add("2");
        cmd.add("-movflags"); cmd.add("+faststart");
        cmd.add("-t"); cmd.add(ffmpegNumber(renderPlan.clipDuration()));
        cmd.add(outputPath.toAbsolutePath().toString());

        executeFFmpeg(cmd);
    }

    private void composeDynamicShot(Path videoPath,
                                    Path audioPath,
                                    Path outputPath,
                                    ShotRenderPlan renderPlan,
                                    Storyboard shot) throws IOException, InterruptedException {
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

        cmd.add("-map"); cmd.add("0:v:0");
        cmd.add("-map"); cmd.add("1:a:0");
        cmd.add("-vf");
        cmd.add(buildDynamicShotFilter(shot, renderPlan));
        cmd.add("-af");
        cmd.add(buildShotAudioFilter(renderPlan.clipDuration()));

        cmd.add("-c:v"); cmd.add("libx264");
        cmd.add("-preset"); cmd.add("medium");
        cmd.add("-crf"); cmd.add("20");
        cmd.add("-pix_fmt"); cmd.add("yuv420p");
        cmd.add("-r"); cmd.add(String.valueOf(FRAME_RATE));
        cmd.add("-c:a"); cmd.add("aac");
        cmd.add("-b:a"); cmd.add("160k");
        cmd.add("-ar"); cmd.add("44100");
        cmd.add("-ac"); cmd.add("2");
        cmd.add("-movflags"); cmd.add("+faststart");
        cmd.add("-t"); cmd.add(ffmpegNumber(renderPlan.clipDuration()));
        cmd.add(outputPath.toAbsolutePath().toString());

        executeFFmpeg(cmd);
    }

    private String buildStillShotFilter(Storyboard shot, ShotRenderPlan renderPlan, boolean microMotionEnabled) {
        String filter = buildStillMotionFilter(shot, renderPlan.clipDuration(), microMotionEnabled);
        String subtitleFilter = buildSubtitleFilter(renderPlan.subtitleText(), renderPlan.subtitleVisibleDuration());
        return hasText(subtitleFilter) ? filter + "," + subtitleFilter : filter;
    }

    private String buildDynamicShotFilter(Storyboard shot, ShotRenderPlan renderPlan) {
        String filter = String.format(Locale.ROOT,
                "fps=%d,scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,tpad=stop_mode=clone:stop_duration=%s",
                FRAME_RATE,
                VIDEO_WIDTH,
                VIDEO_HEIGHT,
                VIDEO_WIDTH,
                VIDEO_HEIGHT,
                ffmpegNumber(renderPlan.clipDuration()));
        String subtitleFilter = buildSubtitleFilter(renderPlan.subtitleText(), renderPlan.subtitleVisibleDuration());
        return hasText(subtitleFilter) ? filter + "," + subtitleFilter : filter;
    }

    private String buildStillMotionFilter(Storyboard shot, double durationSeconds, boolean microMotionEnabled) {
        int totalFrames = Math.max(1, (int) Math.ceil(durationSeconds * FRAME_RATE));
        double zoomStep = 0.0011d;
        double zoomMax = 1.24d;
        String xExpr = "iw/2-(iw/zoom/2)";
        String yExpr = "ih/2-(ih/zoom/2)";
        String cameraAngle = normalizeCameraAngle(shot != null ? shot.getCameraAngle() : null);
        String motionTier = resolveMotionTier(shot);
        int motionPattern = shot != null && shot.getShotNo() != null ? Math.floorMod(shot.getShotNo(), 3) : 0;
        if ("C".equalsIgnoreCase(motionTier)) {
            zoomStep = 0.0006d;
            zoomMax = 1.10d;
        } else if ("B".equalsIgnoreCase(motionTier)) {
            zoomStep = 0.0012d;
            zoomMax = 1.22d;
            if (motionPattern == 1) {
                xExpr = "iw/2-(iw/zoom/2)+sin(on/16)*14";
                yExpr = "ih/2-(ih/zoom/2)+cos(on/18)*8";
            } else if (motionPattern == 2) {
                xExpr = "iw/2-(iw/zoom/2)+sin(on/20)*10";
                yExpr = "ih/2-(ih/zoom/2)-on*0.08";
            }
        }
        if (isHighMotion(shot)) {
            zoomStep = 0.0016d;
            zoomMax = 1.30d;
        }
        if (microMotionEnabled) {
            zoomStep = Math.max(zoomStep, 0.0019d);
            zoomMax = Math.max(zoomMax, 1.36d);
            xExpr = "iw/2-(iw/zoom/2)+sin(on/14)*16";
            yExpr = "ih/2-(ih/zoom/2)+cos(on/16)*10";
        }
        switch (cameraAngle) {
            case "close-up", "closeup" -> {
                zoomStep = Math.max(zoomStep, 0.0018d);
                zoomMax = Math.max(zoomMax, 1.34d);
            }
            case "wide" -> {
                zoomStep = 0.0007d;
                zoomMax = 1.12d;
            }
            case "overhead" -> {
                zoomStep = 0.0010d;
                zoomMax = 1.18d;
                yExpr = "ih/2-(ih/zoom/2)-on*0.12";
            }
            case "pov" -> {
                zoomStep = 0.0013d;
                zoomMax = Math.max(zoomMax, 1.25d);
                xExpr = "iw/2-(iw/zoom/2)+sin(on/18)*18";
                yExpr = "ih/2-(ih/zoom/2)+cos(on/20)*12";
            }
            default -> {
            }
        }
        return String.format(Locale.ROOT,
                "scale=%d:%d:force_original_aspect_ratio=decrease," +
                        "pad=%d:%d:(ow-iw)/2:(oh-ih)/2," +
                        "zoompan=z='min(zoom+%.4f,%.2f)':x='%s':y='%s':d=%d:s=%dx%d:fps=%d",
                VIDEO_WIDTH,
                VIDEO_HEIGHT,
                VIDEO_WIDTH,
                VIDEO_HEIGHT,
                zoomStep,
                zoomMax,
                xExpr,
                yExpr,
                totalFrames,
                VIDEO_WIDTH,
                VIDEO_HEIGHT,
                FRAME_RATE);
    }

    private String buildShotAudioFilter(double durationSeconds) {
        return String.format(Locale.ROOT,
                "aformat=sample_rates=44100:channel_layouts=stereo,atrim=duration=%s,volume=1.06,apad=whole_dur=%s",
                ffmpegNumber(durationSeconds),
                ffmpegNumber(durationSeconds));
    }

    private String buildSubtitleFilter(String subtitleText, double visibleDurationSeconds) {
        if (!composeSubtitleEnabled || !hasText(subtitleText)) {
            return null;
        }
        double visibleDuration = Math.max(1.0d, visibleDurationSeconds);
        StringBuilder filter = new StringBuilder("drawtext=");
        String fontOption = resolveSubtitleFontOption();
        if (hasText(fontOption)) {
            filter.append(fontOption).append(":");
        }
        filter.append("text='").append(escapeDrawtextText(subtitleText)).append("'")
                .append(":fontsize=").append(Math.max(28, composeSubtitleFontSize))
                .append(":fontcolor=white")
                .append(":borderw=4")
                .append(":bordercolor=black")
                .append(":box=1")
                .append(":boxcolor=black@0.25")
                .append(":boxborderw=18")
                .append(":line_spacing=10")
                .append(":x=(w-text_w)/2")
                .append(":y=h-text_h-").append(Math.max(80, composeSubtitleMarginBottom)).append("-6*sin(2*PI*t/2.8)")
                .append(":enable='between(t\\,0\\,").append(ffmpegNumber(visibleDuration)).append(")'");
        return filter.toString();
    }

    private String resolveSubtitleFontOption() {
        Path fontPath = resolveSubtitleFontPath();
        if (fontPath == null) {
            return null;
        }
        return "fontfile='" + escapeFilterPath(fontPath.toAbsolutePath().toString()) + "'";
    }

    private Path resolveSubtitleFontPath() {
        if (hasText(composeSubtitleFontPath)) {
            Path configured = toPath(composeSubtitleFontPath.trim());
            if (configured != null && Files.exists(configured)) {
                return configured.toAbsolutePath();
            }
        }
        List<String> candidates = isWindows()
                ? List.of(
                "C:/Windows/Fonts/msyh.ttc",
                "C:/Windows/Fonts/msyhbd.ttc",
                "C:/Windows/Fonts/simhei.ttf")
                : List.of(
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc");
        for (String candidate : candidates) {
            Path path = toPath(candidate);
            if (path != null && Files.exists(path)) {
                return path.toAbsolutePath();
            }
        }
        return null;
    }

    private String escapeDrawtextText(String text) {
        return text.replace("\\", "\\\\")
                .replace(":", "\\:")
                .replace("'", "\\'")
                .replace("%", "\\%")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    private String escapeFilterPath(String text) {
        return text.replace("\\", "/")
                .replace(":", "\\:")
                .replace("'", "\\'");
    }

    /**
     * Compose the final vertical short drama video with cinematic transitions and optional BGM/SFX.
     */
    private void concatenateVideos(List<ShotSegment> videos,
                                   Path outputPath,
                                   Path bgmPath,
                                   Path transitionSfxPath,
                                   Path transitionConflictSfxPath,
                                   Path transitionLyricalSfxPath,
                                   Path ambientPath,
                                   Path narrationPath) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");

        for (ShotSegment video : videos) {
            cmd.add("-i");
            cmd.add(video.videoPath().toAbsolutePath().toString());
        }

        int bgmInputIndex = -1;
        if (bgmPath != null && Files.exists(bgmPath)) {
            bgmInputIndex = videos.size();
            cmd.add("-stream_loop");
            cmd.add("-1");
            cmd.add("-i");
            cmd.add(bgmPath.toAbsolutePath().toString());
        }

        int transitionSfxInputIndex = -1;
        if (transitionSfxPath != null && Files.exists(transitionSfxPath) && videos.size() > 1) {
            transitionSfxInputIndex = cmd.stream().filter("-i"::equals).toArray().length;
            cmd.add("-i");
            cmd.add(transitionSfxPath.toAbsolutePath().toString());
        }
        int transitionConflictSfxInputIndex = -1;
        if (transitionConflictSfxPath != null && Files.exists(transitionConflictSfxPath) && videos.size() > 1) {
            transitionConflictSfxInputIndex = cmd.stream().filter("-i"::equals).toArray().length;
            cmd.add("-i");
            cmd.add(transitionConflictSfxPath.toAbsolutePath().toString());
        }
        int transitionLyricalSfxInputIndex = -1;
        if (transitionLyricalSfxPath != null && Files.exists(transitionLyricalSfxPath) && videos.size() > 1) {
            transitionLyricalSfxInputIndex = cmd.stream().filter("-i"::equals).toArray().length;
            cmd.add("-i");
            cmd.add(transitionLyricalSfxPath.toAbsolutePath().toString());
        }

        int ambientInputIndex = -1;
        if (ambientPath != null && Files.exists(ambientPath) && videos.size() > 0) {
            ambientInputIndex = cmd.stream().filter("-i"::equals).toArray().length;
            cmd.add("-stream_loop");
            cmd.add("-1");
            cmd.add("-i");
            cmd.add(ambientPath.toAbsolutePath().toString());
        }
        int narrationInputIndex = -1;
        if (narrationPath != null && Files.exists(narrationPath)) {
            narrationInputIndex = cmd.stream().filter("-i"::equals).toArray().length;
            cmd.add("-i");
            cmd.add(narrationPath.toAbsolutePath().toString());
        }

        cmd.add("-filter_complex");
        cmd.add(buildFinalCompositionFilter(videos, bgmInputIndex, transitionSfxInputIndex, transitionConflictSfxInputIndex,
                transitionLyricalSfxInputIndex, ambientInputIndex, narrationInputIndex));
        cmd.add("-map");
        cmd.add("[vout]");
        cmd.add("-map");
        cmd.add("[aout]");
        cmd.add("-c:v");
        cmd.add("libx264");
        ComposeExportProfile exportProfile = resolveComposeExportProfile();
        cmd.add("-preset");
        cmd.add(exportProfile.preset());
        cmd.add("-crf");
        cmd.add(String.valueOf(exportProfile.crf()));
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-r");
        cmd.add(String.valueOf(FRAME_RATE));
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add(exportProfile.audioBitrate());
        cmd.add("-ar");
        cmd.add("44100");
        cmd.add("-ac");
        cmd.add("2");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(outputPath.toAbsolutePath().toString());

        executeFFmpeg(cmd);
    }

    private void concatenateVideosSmart(List<ShotSegment> videos,
                                        Path outputPath,
                                        Path workDir,
                                        Path bgmPath,
                                        Path transitionSfxPath,
                                        Path transitionConflictSfxPath,
                                        Path transitionLyricalSfxPath,
                                        Path ambientPath,
                                        Path narrationPath) throws IOException, InterruptedException {
        int maxPerSegment = Math.max(8, composeMaxShotsPerSegment);
        if (!composeSegmentedEnabled || videos.size() <= maxPerSegment) {
            concatenateVideos(videos, outputPath, bgmPath, transitionSfxPath, transitionConflictSfxPath, transitionLyricalSfxPath, ambientPath, narrationPath);
            return;
        }
        List<ShotSegment> segments = new ArrayList<>();
        int segmentIndex = 0;
        for (int i = 0; i < videos.size(); i += maxPerSegment) {
            int end = Math.min(videos.size(), i + maxPerSegment);
            List<ShotSegment> subList = videos.subList(i, end);
            Path segmentFile = workDir.resolve("segment_" + segmentIndex + ".mp4");
            concatenateVideos(subList, segmentFile, null, null, null, null, null, null);
            double segmentDuration = subList.stream().mapToDouble(ShotSegment::clipDuration).sum();
            segments.add(new ShotSegment(segmentFile, segmentDuration, subList.get(0).shot()));
            segmentIndex++;
        }
        concatenateVideos(segments, outputPath, bgmPath, transitionSfxPath, transitionConflictSfxPath, transitionLyricalSfxPath, ambientPath, narrationPath);
    }

    private String buildFinalCompositionFilter(List<ShotSegment> segments,
                                               int bgmInputIndex,
                                               int transitionSfxInputIndex,
                                               int transitionConflictSfxInputIndex,
                                               int transitionLyricalSfxInputIndex,
                                               int ambientInputIndex,
                                               int narrationInputIndex) {
        List<String> filterSteps = new ArrayList<>();
        List<Double> transitionMarkers = new ArrayList<>();
        List<TransitionMood> transitionMoods = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            filterSteps.add(String.format(Locale.ROOT,
                    "[%d:v]settb=AVTB,setpts=PTS-STARTPTS[v%d]",
                    i,
                    i));
            filterSteps.add(String.format(Locale.ROOT,
                    "[%d:a]aformat=sample_rates=44100:channel_layouts=stereo,asetpts=PTS-STARTPTS[a%d]",
                    i,
                    i));
        }

        String currentVideo = "v0";
        String currentAudio = "a0";
        double currentDuration = segments.get(0).clipDuration();

        if (segments.size() > 1) {
            for (int i = 1; i < segments.size(); i++) {
                double transitionDuration = resolvePairTransitionDuration(
                        segments.get(i - 1).shot(), segments.get(i).shot());
                if (transitionDuration <= 0d) {
                    transitionDuration = 0.18d;
                }
                double offset = Math.max(currentDuration - transitionDuration, 0d);
                transitionMarkers.add(offset);
                transitionMoods.add(resolveTransitionMood(segments.get(i - 1).shot(), segments.get(i).shot()));
                String videoOut = "vxf" + i;
                String audioOut = "axf" + i;
                filterSteps.add(String.format(Locale.ROOT,
                        "[%s][v%d]xfade=transition=%s:duration=%s:offset=%s[%s]",
                        currentVideo,
                        i,
                        resolveTransitionName(segments.get(i - 1).shot(), segments.get(i).shot(), i - 1),
                        ffmpegNumber(transitionDuration),
                        ffmpegNumber(offset),
                        videoOut));
                filterSteps.add(String.format(Locale.ROOT,
                        "[%s][a%d]acrossfade=d=%s:c1=tri:c2=tri[%s]",
                        currentAudio,
                        i,
                        ffmpegNumber(transitionDuration),
                        audioOut));
                currentVideo = videoOut;
                currentAudio = audioOut;
                currentDuration = currentDuration + segments.get(i).clipDuration() - transitionDuration;
            }
        }

        String voiceAudio = currentAudio;
        if (composeVoiceLoudnormEnabled) {
            filterSteps.add(String.format(Locale.ROOT,
                    "[%s]loudnorm=I=-16:LRA=7:TP=-1.5[voicenorm]",
                    currentAudio));
            voiceAudio = "voicenorm";
        }
        String mixedAudio = voiceAudio;
        if (bgmInputIndex >= 0) {
            double bgmFadeDuration = Math.min(DEFAULT_BGM_FADE_OUT_SECONDS, currentDuration);
            double bgmFadeStart = Math.max(currentDuration - bgmFadeDuration, 0d);
            filterSteps.add(String.format(Locale.ROOT,
                    "[%d:a]aformat=sample_rates=44100:channel_layouts=stereo,atrim=duration=%s,asetpts=PTS-STARTPTS,volume=%s,afade=t=out:st=%s:d=%s[bgm0]",
                    bgmInputIndex,
                    ffmpegNumber(currentDuration),
                    ffmpegNumber(resolveBgmVolume()),
                    ffmpegNumber(bgmFadeStart),
                    ffmpegNumber(bgmFadeDuration)));
            if (composeAudioDuckingEnabled) {
                filterSteps.add(String.format(Locale.ROOT,
                        "[bgm0][%s]sidechaincompress=threshold=%s:ratio=%s:attack=%d:release=%d[bgmduck]",
                        voiceAudio,
                        ffmpegNumber(Math.min(Math.max(composeAudioDuckingThreshold, 0.005d), 1.0d)),
                        ffmpegNumber(Math.min(Math.max(composeAudioDuckingRatio, 1.0d), 20.0d)),
                        Math.max(1, composeAudioDuckingAttack),
                        Math.max(10, composeAudioDuckingRelease)));
                filterSteps.add(String.format(Locale.ROOT,
                        "[%s][bgmduck]amix=inputs=2:duration=first:weights='1 0.28':normalize=0[amixbgm]",
                        voiceAudio));
            } else {
                filterSteps.add(String.format(Locale.ROOT,
                        "[%s][bgm0]amix=inputs=2:duration=first:weights='1 0.28':normalize=0[amixbgm]",
                        voiceAudio));
            }
            mixedAudio = "amixbgm";
        }

        if (ambientInputIndex >= 0) {
            double ambVol = Math.min(Math.max(composeAmbientVolume, 0.02d), 0.5d);
            filterSteps.add(String.format(Locale.ROOT,
                    "[%d:a]aformat=sample_rates=44100:channel_layouts=stereo,atrim=duration=%s,asetpts=PTS-STARTPTS,volume=%s[ambloop]",
                    ambientInputIndex,
                    ffmpegNumber(currentDuration),
                    ffmpegNumber(ambVol)));
            filterSteps.add(String.format(Locale.ROOT,
                    "[%s][ambloop]amix=inputs=2:duration=first:weights='1 0.2':normalize=0[amixamb]",
                    mixedAudio));
            mixedAudio = "amixamb";
        }

        if (narrationInputIndex >= 0) {
            double narrationVol = Math.min(Math.max(composeNarrationVolume, 0.05d), 0.5d);
            filterSteps.add(String.format(Locale.ROOT,
                    "[%d:a]aformat=sample_rates=44100:channel_layouts=stereo,atrim=duration=%s,asetpts=PTS-STARTPTS,volume=%s[nar0]",
                    narrationInputIndex, ffmpegNumber(currentDuration), ffmpegNumber(narrationVol)));
            String narrationTrack = "nar0";
            if (composeDialoguePriority && composeNarrationDuckingWhenDialogue) {
                filterSteps.add(String.format(Locale.ROOT,
                        "[nar0][%s]sidechaincompress=threshold=%s:ratio=12:attack=10:release=%d[narduck]",
                        voiceAudio,
                        ffmpegNumber(Math.min(Math.max(composeDialogueThreshold, 0.005d), 1.0d)),
                        Math.max(80, composeNarrationRecoverMs)));
                narrationTrack = "narduck";
            }
            filterSteps.add(String.format(Locale.ROOT,
                    "[%s][%s]amix=inputs=2:duration=first:weights='1 0.22':normalize=0[amixnar]",
                    mixedAudio, narrationTrack));
            mixedAudio = "amixnar";
        }

        int resolvedConflictInput = transitionConflictSfxInputIndex >= 0
                ? transitionConflictSfxInputIndex
                : transitionSfxInputIndex;
        int resolvedLyricalInput = transitionLyricalSfxInputIndex >= 0
                ? transitionLyricalSfxInputIndex
                : transitionSfxInputIndex;
        if ((transitionSfxInputIndex >= 0 || resolvedConflictInput >= 0 || resolvedLyricalInput >= 0)
                && !transitionMarkers.isEmpty()) {
            List<String> delayedSfxTracks = new ArrayList<>();
            for (int i = 0; i < transitionMarkers.size(); i++) {
                int sourceIndex = resolveTransitionSfxInputIndex(
                        transitionMoods.get(i),
                        transitionSfxInputIndex,
                        resolvedConflictInput,
                        resolvedLyricalInput);
                if (sourceIndex < 0) {
                    continue;
                }
                String baseLabel = "sfxsrc" + i;
                filterSteps.add(String.format(Locale.ROOT,
                        "[%d:a]aformat=sample_rates=44100:channel_layouts=stereo,atrim=duration=0.8,asetpts=PTS-STARTPTS,volume=%s[%s]",
                        sourceIndex,
                        ffmpegNumber(resolveTransitionSfxVolume()),
                        baseLabel));
                int delayMs = (int) Math.round(Math.max(transitionMarkers.get(i) - 0.05d, 0d) * 1000d);
                String delayed = "sfxd" + i;
                filterSteps.add(String.format(Locale.ROOT,
                        "[%s]adelay=%d|%d[%s]",
                        baseLabel,
                        delayMs,
                        delayMs,
                        delayed));
                delayedSfxTracks.add(delayed);
            }
            if (delayedSfxTracks.isEmpty()) {
                filterSteps.add(String.format(Locale.ROOT, "[%s]anull[aout]", mixedAudio));
            } else {
                StringBuilder mixInputs = new StringBuilder("[").append(mixedAudio).append("]");
                delayedSfxTracks.forEach(label -> mixInputs.append("[").append(label).append("]"));
                filterSteps.add(mixInputs + String.format(Locale.ROOT,
                        "amix=inputs=%d:duration=first:normalize=0[aout]",
                        delayedSfxTracks.size() + 1));
            }
        } else {
            filterSteps.add(String.format(Locale.ROOT, "[%s]anull[aout]", mixedAudio));
        }

        filterSteps.add(String.format(Locale.ROOT, "[%s]format=pix_fmts=yuv420p[vout]", currentVideo));
        return String.join(";", filterSteps);
    }

    private void executeFFmpeg(List<String> cmd) throws IOException, InterruptedException {
        String executable = resolveFfmpegExecutable();
        List<String> effectiveCmd = new ArrayList<>(cmd);
        effectiveCmd.set(0, executable);
        log.debug("执行 FFmpeg 命令: {}", String.join(" ", effectiveCmd));

        ProcessBuilder pb = new ProcessBuilder(effectiveCmd);
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException(buildFfmpegStartFailureMessage(executable), e);
        }

        // Read output as raw bytes because FFmpeg progress often uses '\r' without '\n'.
        // If we rely on readLine(), long-running progress output may block and stall the process.
        List<String> outputLines = new ArrayList<>();
        try (InputStream stream = process.getInputStream()) {
            ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(512);
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                for (int i = 0; i < read; i++) {
                    byte b = chunk[i];
                    if (b == '\n' || b == '\r') {
                        appendFfmpegOutputLine(outputLines, lineBuffer.toString());
                        lineBuffer.reset();
                        continue;
                    }
                    lineBuffer.write(b);
                }
            }
            appendFfmpegOutputLine(outputLines, lineBuffer.toString());
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String tail = outputLines.isEmpty()
                    ? "<no ffmpeg output captured>"
                    : String.join(" | ", outputLines);
            throw new RuntimeException("FFmpeg exited with code " + exitCode
                    + ", command=" + String.join(" ", effectiveCmd)
                    + ", outputTail=" + tail);
        }
    }

    private void appendFfmpegOutputLine(List<String> outputLines, String line) {
        if (!hasText(line)) {
            return;
        }
        String normalized = line.trim();
        if (!hasText(normalized)) {
            return;
        }
        log.trace("FFmpeg: {}", normalized);
        if (outputLines.size() >= 40) {
            outputLines.remove(0);
        }
        outputLines.add(normalized);
    }

    private String executeCommandForOutput(List<String> cmd, String toolName) throws IOException, InterruptedException {
        log.debug("执行{}命令: {}", toolName, String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append(System.lineSeparator());
                }
                output.append(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(toolName + " exited with code " + exitCode + ", output=" + output);
        }
        return output.toString().trim();
    }

    private String resolveFfmpegExecutable() {
        if (hasText(resolvedFfmpegExecutable)) {
            return resolvedFfmpegExecutable;
        }

        String configured = normalizeExecutableConfig(ffmpegPath);
        Path configuredPath = toPath(configured);
        if (configuredPath != null && Files.exists(configuredPath)) {
            resolvedFfmpegExecutable = configuredPath.toAbsolutePath().toString();
            log.info("FFmpeg 可执行文件来源(配置路径): {}", resolvedFfmpegExecutable);
            return resolvedFfmpegExecutable;
        }

        if (isWindows() && !configured.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            Path configuredExePath = toPath(configured + ".exe");
            if (configuredExePath != null && Files.exists(configuredExePath)) {
                resolvedFfmpegExecutable = configuredExePath.toAbsolutePath().toString();
                log.info("FFmpeg 可执行文件来源(配置路径补全.exe): {}", resolvedFfmpegExecutable);
                return resolvedFfmpegExecutable;
            }
        }

        String foundOnPath = findOnSystemPath(configured);
        if (hasText(foundOnPath)) {
            resolvedFfmpegExecutable = foundOnPath;
            log.info("FFmpeg 可执行文件来源(PATH): {}", resolvedFfmpegExecutable);
            return resolvedFfmpegExecutable;
        }

        if (isWindows()) {
            String foundExeOnPath = findOnSystemPath(configured + ".exe");
            if (hasText(foundExeOnPath)) {
                resolvedFfmpegExecutable = foundExeOnPath;
                log.info("FFmpeg 可执行文件来源(PATH补全.exe): {}", resolvedFfmpegExecutable);
                return resolvedFfmpegExecutable;
            }
        }

        // Fallback to configured value; startup error will include detailed diagnostics.
        resolvedFfmpegExecutable = configured;
        return resolvedFfmpegExecutable;
    }

    private String resolveFfprobeExecutable() {
        if (hasText(resolvedFfprobeExecutable)) {
            return resolvedFfprobeExecutable;
        }

        if (hasText(ffprobePath)) {
            String configured = normalizeExecutableConfig(ffprobePath);
            Path configuredPath = toPath(configured);
            if (configuredPath != null && Files.exists(configuredPath)) {
                resolvedFfprobeExecutable = configuredPath.toAbsolutePath().toString();
                return resolvedFfprobeExecutable;
            }
            if (isWindows() && !configured.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                Path configuredExePath = toPath(configured + ".exe");
                if (configuredExePath != null && Files.exists(configuredExePath)) {
                    resolvedFfprobeExecutable = configuredExePath.toAbsolutePath().toString();
                    return resolvedFfprobeExecutable;
                }
            }
        }

        Path ffmpegExecutable = toPath(resolveFfmpegExecutable());
        if (ffmpegExecutable != null && ffmpegExecutable.getParent() != null) {
            String probeName = isWindows() ? "ffprobe.exe" : "ffprobe";
            Path sibling = ffmpegExecutable.getParent().resolve(probeName);
            if (Files.exists(sibling)) {
                resolvedFfprobeExecutable = sibling.toAbsolutePath().toString();
                return resolvedFfprobeExecutable;
            }
        }

        String foundOnPath = findOnSystemPath(isWindows() ? "ffprobe.exe" : "ffprobe");
        if (hasText(foundOnPath)) {
            resolvedFfprobeExecutable = foundOnPath;
            return resolvedFfprobeExecutable;
        }

        resolvedFfprobeExecutable = isWindows() ? "ffprobe.exe" : "ffprobe";
        return resolvedFfprobeExecutable;
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

    private double measureMediaDurationSeconds(Path mediaPath) {
        if (mediaPath == null || !Files.exists(mediaPath)) {
            return 0d;
        }
        try {
            List<String> cmd = List.of(
                    resolveFfprobeExecutable(),
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    mediaPath.toAbsolutePath().toString());
            String output = executeCommandForOutput(cmd, "ffprobe");
            return hasText(output) ? Double.parseDouble(output.trim()) : 0d;
        } catch (Exception e) {
            log.warn("探测媒体时长失败: path={}, reason={}", mediaPath, buildFailureReason(e));
            return 0d;
        }
    }

    private ShotRenderPlan buildShotRenderPlan(Storyboard shot, double audioDuration, double transitionTail) {
        double cap = composeMaxShotDurationSeconds > 0.5d ? composeMaxShotDurationSeconds : 5.0d;
        double requestedDuration = shot.getDuration() != null && shot.getDuration() > 0
                ? Math.min(shot.getDuration(), cap)
                : Math.min(DEFAULT_SHOT_DURATION_SECONDS, cap);
        double contentDuration;
        if (composeVoiceDrivenSyncEnabled && audioDuration > 0.01d) {
            double pauseSeconds = Math.min(Math.max(composeSyncPauseSeconds, MIN_VOICE_DRIVEN_PAUSE_SECONDS), MAX_VOICE_DRIVEN_PAUSE_SECONDS);
            double voiceDrivenTarget = audioDuration
                    + Math.max(0.1d, composeAudioTailPadding)
                    + Math.max(0d, composeAudioBetweenShotsSeconds)
                    + pauseSeconds;
            double maxAllowedByOverrun = requestedDuration + Math.max(0d, composeSyncMaxOverrunSeconds);
            contentDuration = Math.max(requestedDuration, Math.min(voiceDrivenTarget, maxAllowedByOverrun));
        } else {
            contentDuration = Math.max(
                    requestedDuration,
                    audioDuration + Math.max(0.1d, composeAudioTailPadding) + Math.max(0d, composeAudioBetweenShotsSeconds));
        }
        contentDuration = Math.min(contentDuration, cap);
        contentDuration = Math.max(contentDuration, MIN_SHOT_DURATION_SECONDS);
        double clipDuration = contentDuration + Math.max(0d, transitionTail);
        return new ShotRenderPlan(contentDuration, clipDuration, resolveSubtitleVisibleDuration(shot, contentDuration), resolveSubtitleText(shot));
    }

    private String resolveSubtitleText(Storyboard shot) {
        if (hasText(shot.getSubtitleText())) {
            return DramaTextSanitizer.wrapSubtitleLinesSemantic(
                    DramaTextSanitizer.normalizeSpokenText(shot.getSubtitleText().trim()));
        }
        String raw = DramaTextSanitizer.deriveRawSubtitle(shot, composeSubtitleIncludeNarration,
                composeSubtitleStripSpeakerPrefix, composeSubtitleStripSpeakerPrefix);
        return DramaTextSanitizer.wrapSubtitleLinesSemantic(raw);
    }

    private double resolveSubtitleVisibleDuration(Storyboard shot, double contentDuration) {
        double base = Math.max(0.9d, contentDuration);
        if (contentDuration <= 3.1d || isConflictTone(coalesceMoodText(shot))) {
            return Math.max(0.8d, base - 0.18d);
        }
        return base;
    }

    private double resolvePairTransitionDuration(Storyboard previous, Storyboard next) {
        if (next == null) {
            return resolveTransitionDuration(1);
        }
        String text = (previous != null ? coalesceMoodText(previous) : "")
                + (next != null ? coalesceMoodText(next) : "");
        if (isLyricalTone(text)) {
            return Math.min(Math.max(composeTransitionLyricalSeconds, 0.35d), 1.2d);
        }
        if (isConflictTone(text)) {
            return Math.min(Math.max(composeTransitionConflictSeconds, 0.1d), 0.5d);
        }
        return resolveTransitionDuration(2);
    }

    private String coalesceMoodText(Storyboard s) {
        if (s == null) {
            return "";
        }
        return (hasText(s.getDescription()) ? s.getDescription() : "")
                + (hasText(s.getDialogue()) ? s.getDialogue() : "")
                + (hasText(s.getNarration()) ? s.getNarration() : "");
    }

    private boolean isConflictTone(String text) {
        if (!hasText(text)) {
            return false;
        }
        for (String k : new String[] {"怒", "骂", "打", "滚", "该死", "杀", "吵", "战", "怼", "打脸", "质问"}) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLyricalTone(String text) {
        if (!hasText(text)) {
            return false;
        }
        for (String k : new String[] {"泪", "雨", "回忆", "想你了", "温柔", "吻", "抱", "想你", "告白", "晚安"}) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRenderableMedia(Storyboard shot) {
        if (shot == null) {
            return false;
        }
        boolean renderable = hasText(shot.getVideoUrl());
        if (!renderable) {
            log.warn("镜头缺少分镜视频，跳过合成: shotNo={}", shot.getShotNo());
        }
        return renderable;
    }

    private double resolveTransitionDuration(int shotCount) {
        if (shotCount <= 1) {
            return 0d;
        }
        double configured = composeTransitionDuration > 0d ? composeTransitionDuration : 0.45d;
        return Math.min(Math.max(configured, 0.18d), 1.2d);
    }

    private double resolveBgmVolume() {
        return Math.min(Math.max(composeBgmVolume, 0.02d), 0.8d);
    }

    private double resolveTransitionSfxVolume() {
        return Math.min(Math.max(composeTransitionSfxVolume, 0.02d), 1.0d);
    }

    private TransitionMood resolveTransitionMood(Storyboard previous, Storyboard next) {
        String text = (previous != null ? coalesceMoodText(previous) : "")
                + (next != null ? coalesceMoodText(next) : "");
        if (isConflictTone(text)) {
            return TransitionMood.CONFLICT;
        }
        if (isLyricalTone(text)) {
            return TransitionMood.LYRICAL;
        }
        return TransitionMood.DEFAULT;
    }

    private int resolveTransitionSfxInputIndex(TransitionMood mood,
                                               int defaultInputIndex,
                                               int conflictInputIndex,
                                               int lyricalInputIndex) {
        if (!composeTransitionSfxFollowEmotion) {
            return defaultInputIndex;
        }
        if (mood == TransitionMood.CONFLICT && conflictInputIndex >= 0) {
            return conflictInputIndex;
        }
        if (mood == TransitionMood.LYRICAL && lyricalInputIndex >= 0) {
            return lyricalInputIndex;
        }
        return defaultInputIndex >= 0 ? defaultInputIndex : (conflictInputIndex >= 0 ? conflictInputIndex : lyricalInputIndex);
    }

    private boolean shouldApplyMicroMotion(Storyboard shot,
                                           boolean dynamicSource,
                                           int consecutiveStaticShots,
                                           double consecutiveStaticSeconds) {
        if (!composeAutoMicroMotionEnabled || dynamicSource || shot == null) {
            return false;
        }
        if (shouldUseDynamicVideo(shot) || isHighMotion(shot)) {
            return false;
        }
        return consecutiveStaticShots >= Math.max(1, composeRhythmMaxStaticShots)
                || consecutiveStaticSeconds >= Math.max(4d, composeRhythmMaxStaticSeconds);
    }

    private record ComposeExportProfile(String preset, int crf, String audioBitrate) {}

    private ComposeExportProfile resolveComposeExportProfile() {
        String normalized = hasText(composeExportProfile) ? composeExportProfile.trim().toLowerCase(Locale.ROOT) : "publish";
        if ("preview".equals(normalized)) {
            return new ComposeExportProfile(
                    hasText(composePreviewPreset) ? composePreviewPreset.trim() : "veryfast",
                    Math.min(Math.max(composePreviewCrf, 16), 34),
                    hasText(composePreviewAudioBitrate) ? composePreviewAudioBitrate.trim() : "128k");
        }
        return new ComposeExportProfile(
                hasText(composePublishPreset) ? composePublishPreset.trim() : "slow",
                Math.min(Math.max(composePublishCrf, 14), 30),
                hasText(composePublishAudioBitrate) ? composePublishAudioBitrate.trim() : "192k");
    }

    private String resolveTransitionName(Storyboard previous, Storyboard next, int index) {
        String prevTier = resolveMotionTier(previous);
        String nextTier = resolveMotionTier(next);
        if ("A".equalsIgnoreCase(prevTier) || "A".equalsIgnoreCase(nextTier)
                || shouldUseDynamicVideo(previous) || shouldUseDynamicVideo(next) || isHighMotion(previous) || isHighMotion(next)) {
            return index % 2 == 0 ? "smoothleft" : "smoothup";
        }
        if ("B".equalsIgnoreCase(prevTier) || "B".equalsIgnoreCase(nextTier)) {
            return index % 2 == 0 ? "fade" : "wipeleft";
        }
        String nextAngle = normalizeCameraAngle(next != null ? next.getCameraAngle() : null);
        return switch (nextAngle) {
            case "close-up", "closeup" -> "fadewhite";
            case "overhead" -> "fadeblack";
            case "wide" -> "fade";
            default -> DEFAULT_TRANSITIONS.get(index % DEFAULT_TRANSITIONS.size());
        };
    }

    private boolean isHighMotion(Storyboard shot) {
        return shot != null && hasText(shot.getMotionLevel()) && "high".equalsIgnoreCase(shot.getMotionLevel());
    }

    private String normalizeCameraAngle(String cameraAngle) {
        if (!hasText(cameraAngle)) {
            return "";
        }
        return cameraAngle.trim().toLowerCase(Locale.ROOT);
    }

    private Path prepareComposeAsset(String source, Path targetPath) throws IOException {
        if (!hasText(source)) {
            return null;
        }
        try {
            if (isRemoteSource(source) || source.startsWith(baseUrl)) {
                downloadFile(source, targetPath);
                return Files.exists(targetPath) ? targetPath : null;
            }
            Path localPath = toPath(source.trim());
            if (localPath != null && Files.exists(localPath)) {
                return localPath.toAbsolutePath();
            }
        } catch (Exception e) {
            log.warn("准备可选合成素材失败，已跳过: source={}, reason={}", source, buildFailureReason(e));
            return null;
        }
        log.warn("可选合成素材不存在，已跳过: source={}", source);
        return null;
    }

    private boolean isRemoteSource(String source) {
        return source.startsWith("http://") || source.startsWith("https://");
    }

    private String resolveAssetExtension(String source, String defaultExtension) {
        if (!hasText(source)) {
            return defaultExtension;
        }
        try {
            String path = isRemoteSource(source) ? URI.create(source).getPath() : source;
            if (!hasText(path)) {
                return defaultExtension;
            }
            int slashIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            int dotIndex = path.lastIndexOf('.');
            if (dotIndex > slashIndex) {
                return path.substring(dotIndex);
            }
        } catch (Exception ignored) {
            // Fall back to default extension.
        }
        return defaultExtension;
    }

    private String ffmpegNumber(double value) {
        return String.format(Locale.ROOT, "%.3f", Math.max(0d, value));
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
        log.debug("调度动态视频轮询: taskId={}, projectId={}, runAt={}", taskId, projectId, runAt);
        aiPollScheduler.schedule(
                () -> selfProvider.getObject().pollDynamicVideoTasksAsync(userId, projectId, taskId),
                runAt);
    }

    private boolean isDynamicVideoTaskTimedOut(TaskRecord task) {
        LocalDateTime createTime = task.getCreateTime();
        Duration timeout = Duration.ofMinutes(Math.max(5L, dynamicVideoTimeoutMinutes));
        return createTime != null && Duration.between(createTime, LocalDateTime.now()).compareTo(timeout) >= 0;
    }

    private long resolveDynamicPollDelaySeconds(TaskRecord task) {
        long base = Math.max(2L, dynamicVideoPollBaseSeconds);
        long max = Math.max(base, dynamicVideoPollMaxSeconds);
        if (task == null || task.getCreateTime() == null) {
            return base;
        }
        long elapsed = Duration.between(task.getCreateTime(), LocalDateTime.now()).getSeconds();
        if (elapsed < 60) {
            return base;
        }
        if (elapsed < 180) {
            return Math.min(max, base * 2);
        }
        return max;
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
        List<Map<String, Object>> asyncItems = buildDynamicVideoTaskItems(taskId);
        long tierA = asyncItems.stream().filter(item -> "A".equalsIgnoreCase(String.valueOf(item.get("motionTier")))).count();
        long tierB = asyncItems.stream().filter(item -> "B".equalsIgnoreCase(String.valueOf(item.get("motionTier")))).count();
        long tierC = asyncItems.stream().filter(item -> "C".equalsIgnoreCase(String.valueOf(item.get("motionTier")))).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("generated", generated);
        summary.put("failed", failed);
        summary.put("pending", pending);
        summary.put("tierA", tierA);
        summary.put("tierB", tierB);
        summary.put("tierC", tierC);
        summary.put("asyncTasks", asyncItems);
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
            item.put("motionTier", hasText(shot.getMotionTier()) ? shot.getMotionTier() : "C");
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
            log.warn("合并 AI 任务追踪结果失败", e);
            return buildTaskTraceResult(mediaType, projectId,
                    newCalls != null ? newCalls : objectMapper.createArrayNode(),
                    newOmittedTraceCalls,
                    summary);
        }
    }

    private String buildComposeTaskResult(String videoUrl, List<ShotSegment> shotVideos, Path narrationTrack) {
        if (!hasText(videoUrl)) {
            return videoUrl;
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("videoUrl", videoUrl);
            int total = shotVideos != null ? shotVideos.size() : 0;
            long videoShotCount = shotVideos == null ? 0 : shotVideos.stream()
                    .filter(segment -> segment.shot() != null && hasText(segment.shot().getVideoUrl()))
                    .count();
            root.put("totalShots", total);
            root.put("videoShots", videoShotCount);
            root.put("videoRatio", total <= 0 ? 0d : videoShotCount * 1.0d / total);
            root.put("globalNarrationEnabled", narrationTrack != null && Files.exists(narrationTrack));
            if (narrationTrack != null && Files.exists(narrationTrack)) {
                root.put("globalNarrationDurationSeconds", measureMediaDurationSeconds(narrationTrack));
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return videoUrl;
        }
    }

    private Path generateGlobalNarrationTrack(Long userId, List<ShotSegment> shotVideos, Path workDir) {
        if (!composeNarrationEnabled || shotVideos == null || shotVideos.isEmpty()) {
            return null;
        }
        try {
            List<NarrationSegment> segments = buildNarrationTimeline(shotVideos);
            if (segments.isEmpty()) {
                return null;
            }
            TtsProvider ttsProvider = aiProviderFactory.getTtsProvider(userId);
            List<Path> segmentAudios = new ArrayList<>();
            List<Long> delays = new ArrayList<>();
            List<Double> durations = new ArrayList<>();
            int index = 0;
            for (NarrationSegment segment : segments) {
                byte[] audio = ttsProvider.synthesize(segment.text(), "Cherry", 1.0f, 1.0f,
                        "旁白短句，克制平稳，避免抢对白", "Chinese");
                if (audio == null || audio.length <= 100) {
                    continue;
                }
                Path segmentPath = workDir.resolve("narration_seg_" + index + ".mp3");
                Files.write(segmentPath, audio);
                segmentAudios.add(segmentPath);
                delays.add(Math.max(0L, segment.startMs()));
                durations.add(Math.max(0.6d, Math.min(segment.maxSeconds(), measureMediaDurationSeconds(segmentPath))));
                index++;
            }
            if (segmentAudios.isEmpty()) {
                return null;
            }
            double totalDuration = shotVideos.stream().mapToDouble(ShotSegment::clipDuration).sum() + 0.2d;
            Path output = workDir.resolve("global_narration.m4a");
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegPath);
            cmd.add("-y");
            cmd.add("-f");
            cmd.add("lavfi");
            cmd.add("-i");
            cmd.add("anullsrc=r=44100:cl=stereo:d=" + ffmpegNumber(totalDuration));
            for (Path segmentAudio : segmentAudios) {
                cmd.add("-i");
                cmd.add(segmentAudio.toAbsolutePath().toString());
            }
            StringBuilder fc = new StringBuilder();
            for (int i = 0; i < segmentAudios.size(); i++) {
                if (i > 0) {
                    fc.append(";");
                }
                int delay = Math.toIntExact(Math.min(Integer.MAX_VALUE, delays.get(i)));
                fc.append(String.format(Locale.ROOT,
                        "[%d:a]aformat=sample_rates=44100:channel_layouts=stereo,atrim=duration=%s,adelay=%d|%d[n%d]",
                        i + 1, ffmpegNumber(durations.get(i)), delay, delay, i));
            }
            StringBuilder mixInputs = new StringBuilder("[0:a]");
            for (int i = 0; i < segmentAudios.size(); i++) {
                mixInputs.append("[n").append(i).append("]");
            }
            fc.append(";").append(mixInputs)
                    .append("amix=inputs=").append(segmentAudios.size() + 1)
                    .append(":duration=first:normalize=0[aout]");
            cmd.add("-filter_complex");
            cmd.add(fc.toString());
            cmd.add("-map");
            cmd.add("[aout]");
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add("128k");
            cmd.add(output.toAbsolutePath().toString());
            executeFFmpeg(cmd);
            return Files.exists(output) ? output : null;
        } catch (Exception e) {
            log.warn("生成整集旁白轨失败，已回退无旁白: {}", buildFailureReason(e));
            return null;
        }
    }

    private List<NarrationSegment> buildNarrationTimeline(List<ShotSegment> shotVideos) {
        List<NarrationSegment> segments = new ArrayList<>();
        long cursor = 0L;
        for (ShotSegment shotSegment : shotVideos) {
            Storyboard shot = shotSegment.shot();
            double clipDuration = Math.max(0.8d, shotSegment.clipDuration());
            if (shot != null && hasText(shot.getNarration())) {
                String narration = DramaTextSanitizer.normalizeSpokenText(shot.getNarration());
                if (hasText(narration) && !(composeNarrationPauseOnConflict && isConflictTone(coalesceMoodText(shot)))) {
                    boolean strongDialogue = hasText(shot.getDialogue()) && shot.getDialogue().length() >= 14;
                    if (!strongDialogue) {
                        segments.add(new NarrationSegment(
                                cursor,
                                trimNarrationSegment(narration),
                                Math.min(composeNarrationMaxSegmentSeconds, clipDuration * 0.75d)));
                    }
                }
            }
            cursor += Math.round(clipDuration * 1000d);
        }
        return segments;
    }

    private String trimNarrationSegment(String narration) {
        if (!hasText(narration)) {
            return "";
        }
        String normalized = narration.replace("\n", " ").trim();
        if (normalized.length() <= 38) {
            return normalized;
        }
        return normalized.substring(0, 38);
    }

    private boolean isDynamicClipQualityAcceptable(Path shotVideo) {
        return shotVideo != null && Files.exists(shotVideo);
    }

    private boolean shouldUseDynamicVideo(Storyboard shot) {
        if (shot == null) {
            return false;
        }
        String tier = resolveMotionTier(shot);
        if (hasText(tier) && !"A".equalsIgnoreCase(tier) && !Boolean.TRUE.equals(shot.getDynamicSelected())) {
            return false;
        }
        if (forceDynamicByDefault) {
            if ("image".equalsIgnoreCase(shot.getRenderMode()) && !Boolean.TRUE.equals(shot.getDynamicSelected())) {
                return false;
            }
            return true;
        }
        if (Boolean.TRUE.equals(shot.getDynamicSelected()) || "video".equalsIgnoreCase(shot.getRenderMode())) {
            return true;
        }
        if (targetDynamicRatio >= 0.95d) {
            Integer score = shot.getDynamicScore();
            return score == null || score >= 30;
        }
        return false;
    }

    private String resolveMotionTier(Storyboard shot) {
        if (shot == null || !hasText(shot.getMotionTier())) {
            return "C";
        }
        String normalized = shot.getMotionTier().trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "A", "B", "C" -> normalized;
            default -> "C";
        };
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String buildFailureReason(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return hasText(message) ? message : current.getClass().getSimpleName();
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
                                log.warn("删除临时文件失败: path={}", path);
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("清理目录失败: dir={}", dir, e);
        }
    }

    private void updateTask(TaskRecord task, String status, int progress, String message) {
        log.debug("任务状态更新: taskId={}, taskType={}, status={}, progress={}, message={}",
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
            log.warn("序列化 AI 任务追踪结果失败", e);
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
        String resolvedUrl = extractVideoUrl(videoUrl);
        if (resolvedUrl == null || !resolvedUrl.startsWith(baseUrl)) return null;
        String relativePath = resolvedUrl.substring(baseUrl.length());
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
                log.info("导出后已删除视频文件: {}", videoPath);
            }
        } catch (IOException e) {
            log.warn("导出后删除视频文件失败: path={}", videoPath, e);
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
            log.warn("导出后清理任务结果失败: taskId={}", taskId, e);
        }
        return deleted;
    }

    public String extractVideoUrl(String taskResult) {
        if (!hasText(taskResult)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(taskResult);
            String videoUrl = node.path("videoUrl").asText("");
            if (hasText(videoUrl)) {
                return videoUrl;
            }
        } catch (Exception ignored) {
            // old format: raw url string
        }
        return taskResult;
    }
}
