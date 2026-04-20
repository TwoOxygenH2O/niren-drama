package com.niren.drama.controller;

import com.niren.drama.common.CurrentUserHelper;
import com.niren.drama.common.Result;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.service.StoryboardService;
import com.niren.drama.service.VideoCompositionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Tag(name = "视频合成", description = "分镜图片生成、配音生成、视频合成、成片下载")
@RestController
@RequestMapping("/videos")
@RequiredArgsConstructor
public class VideoController {

    private final StoryboardService storyboardService;
    private final VideoCompositionService videoCompositionService;
    private final CurrentUserHelper currentUserHelper;

    @Operation(summary = "生成分镜图片（异步）")
    @PostMapping("/generate-images/{projectId}")
    public Result<TaskRecord> generateImages(@PathVariable Long projectId,
                                              @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(storyboardService.startGenerateStoryboardImages(userId, projectId));
    }

    @Operation(summary = "生成分镜配音（异步）")
    @PostMapping("/generate-audio/{projectId}")
    public Result<TaskRecord> generateAudio(@PathVariable Long projectId,
                                             @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(storyboardService.startGenerateStoryboardAudio(userId, projectId));
    }

    @Operation(summary = "生成动态镜头片段（异步）")
    @PostMapping("/generate-dynamic/{projectId}")
    public Result<TaskRecord> generateDynamic(@PathVariable Long projectId,
                                              @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(videoCompositionService.startGenerateDynamicVideos(userId, projectId));
    }

    @Operation(summary = "合成视频（异步）")
    @PostMapping("/compose/{projectId}")
    public Result<TaskRecord> compose(@PathVariable Long projectId,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(videoCompositionService.startCompose(userId, projectId));
    }

    @Operation(summary = "获取项目视频合成状态")
    @GetMapping("/status/{projectId}")
    public Result<TaskRecord> getStatus(@PathVariable Long projectId) {
        TaskRecord task = videoCompositionService.getLatestPipelineTask(projectId);
        return Result.success(task);
    }

    @Operation(summary = "获取项目分镜列表（含图片/音频/视频状态）")
    @GetMapping("/storyboards/{projectId}")
    public Result<List<Storyboard>> getStoryboards(@PathVariable Long projectId) {
        return Result.success(storyboardService.listByProject(projectId));
    }

    @Operation(summary = "下载成片视频")
    @GetMapping("/download/{projectId}")
    public ResponseEntity<Resource> download(@PathVariable Long projectId) {
        TaskRecord task = videoCompositionService.getLatestVideoTask(projectId);
        if (task == null || !"SUCCESS".equals(task.getStatus()) || task.getResult() == null) {
            return ResponseEntity.notFound().build();
        }

        Path videoPath = videoCompositionService.getVideoFilePath(task.getResult());
        if (videoPath == null || !Files.exists(videoPath)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(videoPath);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"niren_drama_" + projectId + ".mp4\"")
                .body(resource);
    }

    @Operation(summary = "获取项目合成总览")
    @GetMapping("/overview/{projectId}")
    public Result<Map<String, Object>> getOverview(@PathVariable Long projectId) {
        List<Storyboard> shots = storyboardService.listByProject(projectId);
        TaskRecord latestTask = videoCompositionService.getLatestVideoTask(projectId);

        int totalShots = shots.size();
        long imagesReady = shots.stream().filter(s -> s.getImageUrl() != null && !s.getImageUrl().isBlank()).count();
        long audioReady = shots.stream().filter(s -> s.getAudioUrl() != null && !s.getAudioUrl().isBlank()).count();
        long dynamicRecommended = shots.stream().filter(s -> Boolean.TRUE.equals(s.getDynamicRecommended())).count();
        long dynamicSelected = shots.stream().filter(s -> Boolean.TRUE.equals(s.getDynamicSelected())).count();
        long dynamicReady = shots.stream()
            .filter(s -> Boolean.TRUE.equals(s.getDynamicSelected()))
            .filter(s -> s.getVideoUrl() != null && !s.getVideoUrl().isBlank())
            .count();
        String videoUrl = latestTask != null && "SUCCESS".equals(latestTask.getStatus()) ? latestTask.getResult() : null;

        Map<String, Object> overview = Map.of(
                "totalShots", totalShots,
                "imagesReady", imagesReady,
                "audioReady", audioReady,
                "dynamicRecommended", dynamicRecommended,
                "dynamicSelected", dynamicSelected,
                "dynamicReady", dynamicReady,
                "videoUrl", videoUrl != null ? videoUrl : "",
                "latestTask", latestTask != null ? latestTask : Map.of()
        );
        return Result.success(overview);
    }
}
