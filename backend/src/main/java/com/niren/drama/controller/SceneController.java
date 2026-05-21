package com.niren.drama.controller;

import com.niren.drama.common.Result;
import com.niren.drama.dto.scene.SceneCreateRequest;
import com.niren.drama.entity.Scene;
import com.niren.drama.entity.TaskRecord;


import com.niren.drama.service.SceneService;
import com.niren.drama.service.ProjectService;
import com.niren.drama.common.CurrentUserHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;


import java.util.List;

@Tag(name = "场景管理", description = "场景创建、编辑、AI生成场景图像")
@RestController
@RequestMapping("/scenes")
@RequiredArgsConstructor
public class SceneController {

    private final SceneService sceneService;
    private final ProjectService projectService;
    private final CurrentUserHelper currentUserHelper;

    @Operation(summary = "创建场景")
    @PostMapping
    public Result<Scene> create(@RequestBody @Valid SceneCreateRequest request,
                                @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        projectService.getProject(userId, request.getProjectId()); // ownership check
        return Result.success(sceneService.createScene(request));
    }

    @Operation(summary = "获取项目场景列表")
    @GetMapping("/project/{projectId}")
    public Result<List<Scene>> listByProject(@PathVariable Long projectId,
                                             @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        projectService.getProject(userId, projectId); // ownership check
        return Result.success(sceneService.listByProject(projectId));
    }

    @Operation(summary = "获取场景详情")
    @GetMapping("/{id}")
    public Result<Scene> get(@PathVariable Long id,
                             @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        Scene scene = sceneService.getScene(id);
        projectService.getProject(userId, scene.getProjectId()); // ownership check
        return Result.success(scene);
    }

    @Operation(summary = "更新场景")
    @PutMapping("/{id}")
    public Result<Scene> update(@PathVariable Long id,
                                @RequestBody SceneCreateRequest request,
                                @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        Scene scene = sceneService.getScene(id);
        projectService.getProject(userId, scene.getProjectId()); // ownership check
        return Result.success(sceneService.updateScene(id, request));
    }

    @Operation(summary = "删除场景")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        Scene scene = sceneService.getScene(id);
        projectService.getProject(userId, scene.getProjectId()); // ownership check
        sceneService.deleteScene(id);
        return Result.success();
    }

    @Operation(summary = "AI生成场景图像（异步）")
    @PostMapping("/{id}/generate-image")
    public Result<TaskRecord> generateImage(@PathVariable Long id,
                                            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(sceneService.startGenerateSceneImage(userId, id));
    }

    private Long getUserId(UserDetails userDetails) {
        return currentUserHelper.getUserId(userDetails);
    }
}
