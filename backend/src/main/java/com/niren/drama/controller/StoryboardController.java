package com.niren.drama.controller;

import com.niren.drama.common.Result;
import com.niren.drama.dto.storyboard.StoryboardGenerateRequest;
import com.niren.drama.dto.storyboard.StoryboardPreviewSaveRequest;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;


import com.niren.drama.service.StoryboardService;
import com.niren.drama.common.CurrentUserHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.common.sse.SseTextChunkFanout;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "分镜管理", description = "AI分镜生成、分镜编辑")
@RestController
@RequestMapping("/storyboards")
@RequiredArgsConstructor
public class StoryboardController {

    private static final long SSE_TIMEOUT_MILLIS = 3_600_000L;

    private final StoryboardService storyboardService;
    private final CurrentUserHelper currentUserHelper;
    private final ObjectMapper objectMapper;

    @jakarta.annotation.Resource(name = "aiTaskExecutor")
    private java.util.concurrent.Executor sseExecutor;

    @Value("${niren.ai.sse.typewriter-code-points:2}")
    private int typewriterCodePoints;

    @Operation(summary = "AI生成分镜（异步）")
    @PostMapping("/generate")
    public Result<TaskRecord> generate(@RequestBody @Valid StoryboardGenerateRequest request,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(storyboardService.startGenerateStoryboard(userId, request));
    }

    @Operation(summary = "流式生成分镜预览")
    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@RequestBody @Valid StoryboardGenerateRequest request,
                                     @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        sseExecutor.execute(() -> {
            try {
                storyboardService.streamGenerateStoryboard(userId, request,
                        chunk -> sendContentChunk(emitter, chunk),
                        progress -> sendProgress(emitter, progress));
                sendDone(emitter, "分镜预览生成完成");
                emitter.complete();
            } catch (Exception e) {
                sendError(emitter, e);
            }
        });

        emitter.onTimeout(() -> {
            log.warn("分镜流式生成超时: projectId={}", request.getProjectId());
            sendError(emitter, new RuntimeException("流式生成超时，请缩小生成范围后重试"));
        });
        emitter.onError(e -> log.warn("SSE 连接异常: {}", e.getMessage()));
        return emitter;
    }

    private void sendProgress(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(objectMapper.writeValueAsString(Map.of("message", message)), MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
        }
    }

    @Operation(summary = "保存分镜预览")
    @PostMapping("/preview/save")
    public Result<List<Storyboard>> savePreview(@RequestBody @Valid StoryboardPreviewSaveRequest request,
                                                @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(storyboardService.saveStoryboardPreview(userId, request));
    }

    @Operation(summary = "获取项目下所有分镜")
    @GetMapping("/project/{projectId}")
    public Result<List<Storyboard>> listByProject(@PathVariable Long projectId) {
        return Result.success(storyboardService.listByProject(projectId));
    }

    @Operation(summary = "获取脚本下所有分镜")
    @GetMapping("/script/{scriptId}")
    public Result<List<Storyboard>> listByScript(@PathVariable Long scriptId) {
        return Result.success(storyboardService.listByScript(scriptId));
    }

    @Operation(summary = "获取分镜详情")
    @GetMapping("/{id}")
    public Result<Storyboard> get(@PathVariable Long id) {
        return Result.success(storyboardService.getStoryboard(id));
    }

    @Operation(summary = "更新分镜")
    @PutMapping("/{id}")
    public Result<Storyboard> update(@PathVariable Long id,
                                     @RequestBody Storyboard update) {
        return Result.success(storyboardService.updateStoryboard(id, update));
    }

    private Long getUserId(UserDetails userDetails) {
        return currentUserHelper.getUserId(userDetails);
    }

    private void sendChunk(SseEmitter emitter, String chunk) {
        try {
            emitter.send(SseEmitter.event()
                    .name("chunk")
                    .data(objectMapper.writeValueAsString(Map.of("content", chunk)), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.warn("SSE 发送分片失败: {}", e.getMessage());
        }
    }

    private void sendContentChunk(SseEmitter emitter, String chunk) {
        if (chunk == null) {
            return;
        }
        if (chunk.isEmpty()) {
            sendChunk(emitter, "");
            return;
        }
        if (typewriterCodePoints <= 0) {
            sendChunk(emitter, chunk);
            return;
        }
        SseTextChunkFanout.forEachCodePointSlice(chunk, typewriterCodePoints, sub -> sendChunk(emitter, sub));
    }

    private void sendDone(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(objectMapper.writeValueAsString(Map.of("message", message)), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.warn("SSE 发送完成事件失败: {}", e.getMessage());
        }
    }

    private void sendError(SseEmitter emitter, Exception e) {
        log.error("分镜流式生成失败", e);
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(Map.of("message", e.getMessage())), MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
        }
        emitter.complete();
    }
}
