package com.niren.drama.controller;

import com.niren.drama.common.Result;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Tag(name = "剧本管理", description = "AI生成剧本、剧本编辑")
@RestController
@RequestMapping("/scripts")
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptService scriptService;
    private final CurrentUserHelper currentUserHelper;
    private final ObjectMapper objectMapper;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    @Operation(summary = "AI生成剧本（异步）")
    @PostMapping("/generate")
    public Result<TaskRecord> generate(@RequestBody @Valid ScriptGenerateRequest request,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(scriptService.startGenerateScript(userId, request));
    }

    @Operation(summary = "批量生成多集剧本（异步）")
    @PostMapping("/generate/batch")
    public Result<TaskRecord> generateBatch(@RequestBody @Valid ScriptGenerateRequest request,
                                            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(scriptService.startBatchGenerateScript(userId, request));
    }

    @Operation(summary = "流式生成剧本预览")
    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@RequestBody @Valid ScriptGenerateRequest request,
                                     @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        // 10 minutes timeout for long script generation
        SseEmitter emitter = new SseEmitter(600_000L);

        sseExecutor.execute(() -> {
            try {
                scriptService.streamGenerateScript(userId, request, chunk -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("chunk")
                                .data(objectMapper.writeValueAsString(Map.of("content", chunk)),
                                        MediaType.APPLICATION_JSON));
                    } catch (Exception e) {
                        log.warn("SSE send chunk failed: {}", e.getMessage());
                    }
                });
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(objectMapper.writeValueAsString(Map.of("message", "剧本生成完成")),
                                MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception e) {
                log.error("Script stream generation failed", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(objectMapper.writeValueAsString(Map.of("message", e.getMessage())),
                                    MediaType.APPLICATION_JSON));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.warn("SSE emitter error: {}", e.getMessage()));

        return emitter;
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
        return Result.success(scriptService.updateScript(id, body.get("content"), body.get("title")));
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
}
