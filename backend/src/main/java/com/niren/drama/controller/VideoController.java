package com.niren.drama.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.niren.drama.common.CurrentUserHelper;
import com.niren.drama.common.Result;
import com.niren.drama.dto.video.ReferenceVideoTaskResponse;
import com.niren.drama.dto.video.ReferenceVideoTaskStatusResponse;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.service.ReferenceVideoService;
import com.niren.drama.service.StoryboardService;
import com.niren.drama.service.VideoCompositionService;
import com.niren.drama.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "视频合成", description = "分镜图片生成、配音生成、视频合成、成片下载")
@RestController
@RequestMapping("/videos")
@RequiredArgsConstructor
public class VideoController {

    private final StoryboardService storyboardService;
    private final VideoCompositionService videoCompositionService;
    private final ReferenceVideoService referenceVideoService;
    private final ProjectService projectService;
    private final CurrentUserHelper currentUserHelper;

    private List<Long> parseShotIds(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }

        JsonNode shotIdsNode = body.isArray() ? body : body.get("shotIds");
        if (shotIdsNode == null || shotIdsNode.isNull()) {
            return List.of();
        }
        if (!shotIdsNode.isArray()) {
            throw new BusinessException("shotIds 参数格式不正确");
        }

        List<Long> shotIds = new ArrayList<>();
        for (JsonNode node : shotIdsNode) {
            String rawValue = node.asText();
            if (rawValue == null || rawValue.isBlank()) {
                continue;
            }
            try {
                shotIds.add(Long.valueOf(rawValue));
            } catch (NumberFormatException ex) {
                throw new BusinessException("shotIds 参数格式不正确");
            }
        }
        return shotIds;
    }

    private List<Long> parseRequestedShotIds(JsonNode body) {
        List<Long> shotIds = parseShotIds(body);
        if (body != null && shotIds != null && shotIds.isEmpty()) {
            throw new BusinessException("请至少选择一个分镜");
        }
        return shotIds;
    }

    private VideoCompositionService.ComposeOptions parseComposeOptions(JsonNode body) {
        if (body == null || !body.isObject()) {
            return null;
        }
        return new VideoCompositionService.ComposeOptions(
                body.has("narrationEnabled") ? body.path("narrationEnabled").asBoolean() : null,
                body.has("narrationVolume") ? body.path("narrationVolume").asDouble() : null,
                body.has("dialoguePriority") ? body.path("dialoguePriority").asBoolean() : null,
                body.has("bgmEnabled") ? body.path("bgmEnabled").asBoolean() : null,
                body.has("bgmVolume") ? body.path("bgmVolume").asDouble() : null
        );
    }

    @Operation(summary = "生成分镜图片（异步）")
    @PostMapping("/generate-images/{projectId}")
    public Result<TaskRecord> generateImages(@PathVariable Long projectId, @RequestBody(required = false) JsonNode body,
                                              @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        List<Long> shotIds = parseRequestedShotIds(body);
        return Result.success(storyboardService.startGenerateStoryboardImages(userId, projectId, shotIds));
    }

    @Operation(summary = "生成分镜配音（异步）")
    @PostMapping("/generate-audio/{projectId}")
    public Result<TaskRecord> generateAudio(@PathVariable Long projectId, @RequestBody(required = false) JsonNode body,
                                             @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        List<Long> shotIds = parseRequestedShotIds(body);
        return Result.success(storyboardService.startGenerateStoryboardAudio(userId, projectId, shotIds));
    }

    @Operation(summary = "生成动态镜头片段（异步）")
    @PostMapping("/generate-dynamic/{projectId}")
    public Result<TaskRecord> generateDynamic(@PathVariable Long projectId, @RequestBody(required = false) JsonNode body,
                                              @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        List<Long> shotIds = parseRequestedShotIds(body);
        return Result.success(videoCompositionService.startGenerateDynamicVideos(userId, projectId, shotIds));
    }

    @Operation(summary = "合成视频（异步）")
    @PostMapping("/compose/{projectId}")
    public Result<TaskRecord> compose(@PathVariable Long projectId, @RequestBody(required = false) JsonNode body,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        List<Long> shotIds = parseRequestedShotIds(body);
        return Result.success(videoCompositionService.startCompose(userId, projectId, shotIds, parseComposeOptions(body)));
    }

    @Operation(summary = "参考图生成视频（先上传到 COS，再提交万相 2.7 i2v）")
    @PostMapping(value = "/reference/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ReferenceVideoTaskResponse> generateReferenceVideo(@RequestParam String prompt,
                                                                     @RequestParam(required = false) MultipartFile file,
                                                                     @RequestParam(required = false) String referenceImageUrl,
                                                                     @RequestParam(required = false) Integer duration,
                                                                     @RequestParam(required = false) Long projectId,
                                                                     @AuthenticationPrincipal UserDetails userDetails) throws Exception {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(referenceVideoService.submit(userId, projectId, file, referenceImageUrl, prompt, duration));
    }

    @Operation(summary = "查询参考图视频任务状态")
    @GetMapping("/reference/query")
    public Result<ReferenceVideoTaskStatusResponse> queryReferenceVideo(@RequestParam String taskId,
                                                                        @RequestParam(required = false) String statusUrl,
                                                                        @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(referenceVideoService.query(userId, taskId, statusUrl));
    }

    @Operation(summary = "获取项目视频合成状态")
    @GetMapping("/status/{projectId}")
    public Result<TaskRecord> getStatus(@PathVariable Long projectId,
                                        @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        projectService.getProject(userId, projectId);
        TaskRecord task = videoCompositionService.getLatestPipelineTask(projectId);
        return Result.success(task);
    }

    @Operation(summary = "获取项目分镜列表（含图片/音频/视频状态）")
    @GetMapping("/storyboards/{projectId}")
    public Result<List<Storyboard>> getStoryboards(@PathVariable Long projectId,
                                                   @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        projectService.getProject(userId, projectId);
        return Result.success(storyboardService.listByProject(projectId));
    }

    @Operation(summary = "下载成片视频（下载后自动删除文件）")
    @GetMapping("/download/{projectId}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable Long projectId,
                                                          @AuthenticationPrincipal UserDetails userDetails) throws Exception {
        Long userId = currentUserHelper.getUserId(userDetails);
        projectService.getProject(userId, projectId);
        TaskRecord task = videoCompositionService.getLatestVideoTask(projectId);
        String taskVideoUrl = task != null ? videoCompositionService.extractVideoUrl(task.getResult()) : null;
        if (task == null || !"SUCCESS".equals(task.getStatus()) || taskVideoUrl == null) {
            return ResponseEntity.notFound().build();
        }

        Path videoPath = videoCompositionService.getVideoFilePath(taskVideoUrl);
        if (videoPath == null && taskVideoUrl.startsWith("http")) {
            return ResponseEntity.status(302)
                    .header(HttpHeaders.LOCATION, taskVideoUrl)
                    .build();
        }
        if (videoPath == null || !Files.exists(videoPath)) {
            return ResponseEntity.notFound().build();
        }

        long fileSize = Files.size(videoPath);
        Long taskId = task.getId();

        StreamingResponseBody body = outputStream -> {
            try (InputStream in = Files.newInputStream(videoPath)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    outputStream.write(buf, 0, n);
                }
                outputStream.flush();
            } finally {
                // Delete file and clear task result after streaming to save space
                videoCompositionService.deleteVideoAndClearResult(taskId, videoPath);
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"niren_drama_" + projectId + ".mp4\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileSize))
                .body(body);
    }

    @Operation(summary = "获取项目合成总览")
    @GetMapping("/overview/{projectId}")
    public Result<Map<String, Object>> getOverview(@PathVariable Long projectId,
                                                   @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        projectService.getProject(userId, projectId);
        List<Storyboard> shots = storyboardService.listByProject(projectId);
        TaskRecord latestTask = videoCompositionService.getLatestVideoTask(projectId);

        int totalShots = shots.size();
        long imagesReady = shots.stream().filter(s -> s.getImageUrl() != null && !s.getImageUrl().isBlank()).count();
        long audioReady = shots.stream().filter(s -> s.getAudioUrl() != null && !s.getAudioUrl().isBlank()).count();
        long dynamicRecommended = shots.stream().filter(s -> Boolean.TRUE.equals(s.getDynamicRecommended())).count();
        long dynamicSelected = shots.stream().filter(s -> Boolean.TRUE.equals(s.getDynamicSelected())).count();
        long tierA = shots.stream().filter(s -> "A".equalsIgnoreCase(s.getMotionTier())).count();
        long tierB = shots.stream().filter(s -> "B".equalsIgnoreCase(s.getMotionTier())).count();
        long tierC = shots.stream().filter(s -> !("A".equalsIgnoreCase(s.getMotionTier()) || "B".equalsIgnoreCase(s.getMotionTier()))).count();
        long dynamicReady = shots.stream()
            .filter(s -> Boolean.TRUE.equals(s.getDynamicSelected()))
            .filter(s -> s.getVideoUrl() != null && !s.getVideoUrl().isBlank())
            .count();
        String videoUrl = latestTask != null && "SUCCESS".equals(latestTask.getStatus())
                ? videoCompositionService.extractVideoUrl(latestTask.getResult())
                : null;

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalShots", totalShots);
        overview.put("imagesReady", imagesReady);
        overview.put("audioReady", audioReady);
        overview.put("dynamicRecommended", dynamicRecommended);
        overview.put("dynamicSelected", dynamicSelected);
        overview.put("tierA", tierA);
        overview.put("tierB", tierB);
        overview.put("tierC", tierC);
        overview.put("dynamicReady", dynamicReady);
        overview.put("videoUrl", videoUrl != null ? videoUrl : "");
        overview.put("latestTask", latestTask != null ? latestTask : Map.of());
        return Result.success(overview);
    }
}
