package com.niren.drama.controller;

import com.niren.drama.common.PageQuery;
import com.niren.drama.common.PageResult;
import com.niren.drama.common.Result;
import com.niren.drama.dto.project.ProjectCreateRequest;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.User;
import com.niren.drama.mapper.UserMapper;
import com.niren.drama.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

@Tag(name = "项目管理", description = "短剧项目的增删改查")
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final UserMapper userMapper;

    @Operation(summary = "创建项目")
    @PostMapping
    public Result<Project> create(@RequestBody @Valid ProjectCreateRequest request,
                                  @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(projectService.createProject(userId, request));
    }

    @Operation(summary = "项目列表")
    @GetMapping
    public Result<PageResult<Project>> list(PageQuery query,
                                            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(PageResult.of(projectService.listProjects(userId, query)));
    }

    @Operation(summary = "获取项目详情")
    @GetMapping("/{id}")
    public Result<Project> get(@PathVariable Long id) {
        return Result.success(projectService.getProject(id));
    }

    @Operation(summary = "更新项目")
    @PutMapping("/{id}")
    public Result<Project> update(@PathVariable Long id,
                                  @RequestBody ProjectCreateRequest request) {
        return Result.success(projectService.updateProject(id, request));
    }

    @Operation(summary = "删除项目")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.deleteProject(id);
        return Result.success();
    }

    private Long getUserId(UserDetails userDetails) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, userDetails.getUsername()));
        return user.getId();
    }
}
