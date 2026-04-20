package com.niren.drama.controller;

import com.niren.drama.common.Result;
import com.niren.drama.dto.storyboard.StoryboardGenerateRequest;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;


import com.niren.drama.service.StoryboardService;
import com.niren.drama.common.CurrentUserHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;


import java.util.List;

@Tag(name = "分镜管理", description = "AI分镜生成、分镜编辑")
@RestController
@RequestMapping("/storyboards")
@RequiredArgsConstructor
public class StoryboardController {

    private final StoryboardService storyboardService;
    private final CurrentUserHelper currentUserHelper;

    @Operation(summary = "AI生成分镜（异步）")
    @PostMapping("/generate")
    public Result<TaskRecord> generate(@RequestBody @Valid StoryboardGenerateRequest request,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(storyboardService.startGenerateStoryboard(userId, request));
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
}
