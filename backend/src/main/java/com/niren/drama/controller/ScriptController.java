package com.niren.drama.controller;

import com.niren.drama.common.Result;
import com.niren.drama.dto.script.BatchScriptPreviewSaveRequest;
import com.niren.drama.dto.script.OutlinePreviewRepairRequest;
import com.niren.drama.dto.script.OutlinePreviewSaveRequest;
import com.niren.drama.dto.script.ScriptGenerateRequest;
import com.niren.drama.dto.script.ScriptSaveRequest;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.TaskRecord;

import com.niren.drama.service.ScriptService;
import com.niren.drama.common.CurrentUserHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "剧本管理", description = "AI生成剧本、剧本编辑")
@RestController
@RequestMapping("/scripts")
@RequiredArgsConstructor
public class ScriptController {

    private static final long SSE_TIMEOUT_MILLIS = 1_800_000L;

    private final ScriptService scriptService;
    private final CurrentUserHelper currentUserHelper;
    private final ObjectMapper objectMapper;

    @jakarta.annotation.Resource(name = "aiTaskExecutor")
    private java.util.concurrent.Executor sseExecutor;

    @Operation(summary = "AI生成剧本（异步）")
    @PostMapping("/generate")
    public Result<TaskRecord> generate(@RequestBody @Valid ScriptGenerateRequest request,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(scriptService.startGenerateScript(userId, request));
    }

    @Operation(summary = "生成全剧分集大纲与项目通用信息（异步）")
    @PostMapping("/generate/outline")
    public Result<TaskRecord> generateOutline(@RequestBody @Valid ScriptGenerateRequest request,
                                              @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(scriptService.startGenerateOutline(userId, request));
    }

    @Operation(summary = "流式生成分集大纲预览")
    @PostMapping(value = "/generate/outline/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateOutlineStream(@RequestBody @Valid ScriptGenerateRequest request,
                                            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        sseExecutor.execute(() -> {
            try {
                scriptService.streamGenerateOutline(userId, request, chunk -> sendChunk(emitter, chunk));
                sendDone(emitter, "大纲预览生成完成");
                emitter.complete();
            } catch (Exception e) {
                sendError(emitter, e);
            }
        });

        emitter.onTimeout(() -> {
            log.warn("Script outline stream timeout for project {}", request.getProjectId());
            sendError(emitter, new RuntimeException("流式生成超时，请缩小生成范围后重试"));
        });
        emitter.onError(e -> log.warn("SSE emitter error: {}", e.getMessage()));
        return emitter;
    }

    @Operation(summary = "批量生成多集剧本（异步）")
    @PostMapping("/generate/batch")
    public Result<TaskRecord> generateBatch(@RequestBody @Valid ScriptGenerateRequest request,
                                            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(scriptService.startBatchGenerateScript(userId, request));
    }

    @Operation(summary = "流式生成剧本预览（单集/区间）")
    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@RequestBody @Valid ScriptGenerateRequest request,
                                     @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        sseExecutor.execute(() -> {
            try {
                scriptService.streamGenerateScriptPreview(userId, request, chunk -> sendChunk(emitter, chunk));
                sendDone(emitter, "剧本预览生成完成");
                emitter.complete();
            } catch (Exception e) {
                sendError(emitter, e);
            }
        });

        emitter.onTimeout(() -> {
            log.warn("Script preview stream timeout for project {}", request.getProjectId());
            sendError(emitter, new RuntimeException("流式生成超时，请缩小生成范围后重试"));
        });
        emitter.onError(e -> log.warn("SSE emitter error: {}", e.getMessage()));

        return emitter;
    }

    @Operation(summary = "保存大纲预览")
    @PostMapping("/preview/outline/save")
    public Result<Void> saveOutlinePreview(@RequestBody @Valid OutlinePreviewSaveRequest request,
                                           @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        scriptService.saveOutlinePreview(userId, request);
        return Result.success();
    }

    @Operation(summary = "AI修复大纲预览缺失集")
    @PostMapping("/preview/outline/repair")
    public Result<Map<String, Object>> repairOutlinePreview(@RequestBody @Valid OutlinePreviewRepairRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(scriptService.repairOutlinePreview(userId, request));
    }

    @Operation(summary = "保存批量剧本预览")
    @PostMapping("/preview/batch/save")
    public Result<Void> saveBatchPreview(@RequestBody @Valid BatchScriptPreviewSaveRequest request,
                                         @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        scriptService.saveBatchScriptPreview(userId, request);
        return Result.success();
    }

    @Operation(summary = "保存剧本")
    @PostMapping
    public Result<Script> save(@RequestBody @Valid ScriptSaveRequest request,
                               @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(scriptService.saveScript(userId, request));
    }

    @Operation(summary = "获取项目下所有剧本")
    @GetMapping("/project/{projectId}")
    public Result<List<Script>> listByProject(@PathVariable Long projectId) {
        return Result.success(scriptService.listByProject(projectId));
    }

    @Operation(summary = "获取剧本详情")
    @GetMapping("/{id}")
    public Result<Script> get(@PathVariable Long id) {
        return Result.success(scriptService.getScript(id));
    }

    @Operation(summary = "更新剧本内容")
    @PutMapping("/{id}")
    public Result<Script> update(@PathVariable Long id,
                                 @RequestBody Map<String, String> body) {
        return Result.success(scriptService.updateScript(id, body.get("content"), body.get("title"), body.get("summary")));
    }

    @Operation(summary = "删除剧本")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        scriptService.deleteScript(id);
        return Result.success();
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
            log.warn("SSE send chunk failed: {}", e.getMessage());
        }
    }

    private void sendDone(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(objectMapper.writeValueAsString(Map.of("message", message)), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.warn("SSE send done failed: {}", e.getMessage());
        }
    }

    private void sendError(SseEmitter emitter, Exception e) {
        log.error("Script stream generation failed", e);
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(Map.of("message", e.getMessage())), MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
        }
        emitter.completeWithError(e);
    }
}
