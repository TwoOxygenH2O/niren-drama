package com.niren.drama.controller;

import com.niren.drama.common.Result;
import com.niren.drama.dto.character.CharacterCreateRequest;
import com.niren.drama.entity.Character;
import com.niren.drama.entity.TaskRecord;


import com.niren.drama.service.CharacterService;
import com.niren.drama.common.CurrentUserHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;


import java.util.List;

@Tag(name = "角色管理", description = "角色创建、编辑、AI生成角色图像")
@RestController
@RequestMapping("/characters")
@RequiredArgsConstructor
public class CharacterController {

    private final CharacterService characterService;
    private final CurrentUserHelper currentUserHelper;

    @Operation(summary = "创建角色")
    @PostMapping
    public Result<Character> create(@RequestBody @Valid CharacterCreateRequest request) {
        return Result.success(characterService.createCharacter(request));
    }

    @Operation(summary = "获取项目角色列表")
    @GetMapping("/project/{projectId}")
    public Result<List<Character>> listByProject(@PathVariable Long projectId) {
        return Result.success(characterService.listByProject(projectId));
    }

    @Operation(summary = "获取角色详情")
    @GetMapping("/{id}")
    public Result<Character> get(@PathVariable Long id) {
        return Result.success(characterService.getCharacter(id));
    }

    @Operation(summary = "更新角色")
    @PutMapping("/{id}")
    public Result<Character> update(@PathVariable Long id,
                                    @RequestBody CharacterCreateRequest request) {
        return Result.success(characterService.updateCharacter(id, request));
    }

    @Operation(summary = "删除角色")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        characterService.deleteCharacter(id);
        return Result.success();
    }

    @Operation(summary = "AI生成角色图像（异步）")
    @PostMapping("/{id}/generate-image")
    public Result<TaskRecord> generateImage(@PathVariable Long id,
                                            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(characterService.startGenerateCharacterImage(userId, id));
    }

    private Long getUserId(UserDetails userDetails) {
        return currentUserHelper.getUserId(userDetails);
    }
}
