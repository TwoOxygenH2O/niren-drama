package com.niren.drama.service;

import com.niren.drama.entity.Project;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.ProjectMapper;
import com.niren.drama.mapper.StoryboardMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Video composition service using FFmpeg.
 * Assembles storyboard shots (images + audio) into a final video.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoCompositionService {

    private final StoryboardService storyboardService;
    private final StoryboardMapper storyboardMapper;
    private final ProjectMapper projectMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final ObjectProvider<VideoCompositionService> selfProvider;

    @Value("${niren.upload.path:./uploads}")
    private String uploadPath;

    @Value("${niren.upload.base-url:http://localhost:8080/api/files}")
    private String baseUrl;

    @Value("${niren.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

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

        boolean allReadyForDynamic = selectedShots.stream().allMatch(s -> hasText(s.getImageUrl()));
        if (!allReadyForDynamic) {
            throw new BusinessException("仍有已选动态镜头缺少关键帧图片，请先完成分镜图片生成");
        }

        TaskRecord task = new TaskRecord();
        task.setProjectId(projectId);
        task.setUserId(userId);
        task.setTaskType("DYNAMIC_VIDEO_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("动态镜头生成任务已提交...");
        taskRecordMapper.insert(task);

        selfProvider.getObject().generateDynamicVideosAsync(projectId, selectedShots, task.getId());
        return task;
    }

    @Async("aiTaskExecutor")
    public void composeAsync(Long userId, Long projectId, List<Storyboard> shots, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;

        Path workDir = null;
        try {
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
            String videoUrl = baseUrl + "/videos/" + outputFilename;
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
    public void generateDynamicVideosAsync(Long projectId, List<Storyboard> shots, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;

        Path workDir = null;
        try {
            workDir = Paths.get(uploadPath, "dynamic", projectId.toString(), String.valueOf(taskId));
            Files.createDirectories(workDir);

            Path outputDir = Paths.get(uploadPath, SHOT_VIDEO_DIR);
            Files.createDirectories(outputDir);

            int total = shots.size();
            int completed = 0;
            int generated = 0;

            for (Storyboard shot : shots) {
                completed++;
                if (!hasText(shot.getImageUrl())) {
                    log.warn("Dynamic shot {} has no keyframe image, skipped", shot.getShotNo());
                    continue;
                }

                updateTask(task, "RUNNING",
                        10 + (80 * completed / total),
                        String.format("正在生成第%d/%d个动态镜头...", completed, total));

                Path imagePath = workDir.resolve("dynamic_" + shot.getShotNo() + ".jpg");
                downloadFile(shot.getImageUrl(), imagePath);

                String fileName = "shot_" + projectId + "_" + shot.getShotNo() + "_"
                        + UUID.randomUUID().toString().replace("-", "") + ".mp4";
                Path outputPath = outputDir.resolve(fileName);

                renderDynamicShotVideo(imagePath, outputPath,
                        shot.getDuration() != null && shot.getDuration() > 0 ? shot.getDuration() : 5,
                        shot.getMotionLevel());

                if (Files.exists(outputPath) && Files.size(outputPath) > 0) {
                    shot.setVideoUrl(baseUrl + "/" + SHOT_VIDEO_DIR + "/" + fileName);
                    shot.setRenderMode("video");
                    shot.setStatus("video_generated");
                    storyboardMapper.updateById(shot);
                    generated++;
                }
            }

            if (generated == 0) {
                throw new BusinessException("没有成功生成任何动态镜头片段");
            }

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage(String.format("动态镜头生成完成，共生成%d个片段", generated));
            taskRecordMapper.updateById(task);

            cleanupDirectory(workDir);
        } catch (Exception e) {
            log.error("Dynamic video generation failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("动态镜头生成失败: " + e.getMessage());
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
        log.debug("FFmpeg command: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Read output to prevent blocking
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.trace("FFmpeg: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg exited with code " + exitCode);
        }
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
        task.setStatus(status);
        task.setProgress(progress);
        task.setMessage(message);
        taskRecordMapper.updateById(task);
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
        if (videoUrl == null) return null;
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
            deleted = Files.deleteIfExists(videoPath);
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
