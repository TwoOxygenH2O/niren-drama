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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;


import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Tag(name = "剧本管理", description = "AI生成剧本、剧本编辑")
@RestController
@RequestMapping("/scripts")
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptService scriptService;
    private final CurrentUserHelper currentUserHelper;
    private final ObjectMapper objectMapper;

    @Operation(summary = "AI生成剧本（异步）")
    @PostMapping("/generate")
    public Result<TaskRecord> generate(@RequestBody @Valid ScriptGenerateRequest request,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(scriptService.startGenerateScript(userId, request));
    }

    @Operation(summary = "流式生成剧本预览")
    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody generateStream(@RequestBody @Valid ScriptGenerateRequest request,
                                                @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return outputStream -> {
            try {
                scriptService.streamGenerateScript(userId, request, chunk -> writeEvent(outputStream, "chunk",
                        Map.of("content", chunk)));
                writeEvent(outputStream, "done", Map.of("message", "剧本生成完成"));
            } catch (Exception e) {
                writeEvent(outputStream, "error", Map.of("message", e.getMessage()));
            }
        };
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

    private void writeEvent(java.io.OutputStream outputStream, String event, Object data) {
        try {
            outputStream.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write(("data: " + objectMapper.writeValueAsString(data) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
