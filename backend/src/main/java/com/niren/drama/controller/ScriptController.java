package com.niren.drama.controller;

import com.niren.drama.common.Result;
import com.niren.drama.dto.script.ScriptGenerateRequest;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.TaskRecord;


import com.niren.drama.service.ScriptService;
import com.niren.drama.common.CurrentUserHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;


import java.util.List;
import java.util.Map;

@Tag(name = "剧本管理", description = "AI生成剧本、剧本编辑")
@RestController
@RequestMapping("/scripts")
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptService scriptService;
    private final CurrentUserHelper currentUserHelper;

    @Operation(summary = "AI生成剧本（异步）")
    @PostMapping("/generate")
    public Result<TaskRecord> generate(@RequestBody @Valid ScriptGenerateRequest request,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(scriptService.startGenerateScript(userId, request));
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
                .eq(User::getUsername, userDetails.getUsername()));
        return user.getId();
    }
}
