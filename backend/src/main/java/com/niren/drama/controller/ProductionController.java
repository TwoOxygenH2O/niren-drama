package com.niren.drama.controller;

import com.niren.drama.common.CurrentUserHelper;
import com.niren.drama.common.Result;
import com.niren.drama.service.ProductionWorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "短剧生产线", description = "统一生产线工作台、质检、修复、快照和发布包")
@RestController
@RequestMapping("/production")
@RequiredArgsConstructor
public class ProductionController {

    private final ProductionWorkspaceService productionWorkspaceService;
    private final CurrentUserHelper currentUserHelper;

    @Operation(summary = "获取短剧生产线工作台")
    @GetMapping("/{projectId}/workspace")
    public Result<Map<String, Object>> getWorkspace(@PathVariable Long projectId,
                                                    @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(productionWorkspaceService.getWorkspace(userId, projectId));
    }

    @Operation(summary = "执行生产线修复动作")
    @PostMapping("/{projectId}/repair")
    public Result<Map<String, Object>> repair(@PathVariable Long projectId,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(productionWorkspaceService.repair(userId, projectId, body == null ? Map.of() : body));
    }

    @Operation(summary = "运行本地启发式质检")
    @PostMapping("/{projectId}/quality-check")
    public Result<Map<String, Object>> runQualityCheck(@PathVariable Long projectId,
                                                       @RequestBody(required = false) Map<String, Object> body,
                                                       @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(productionWorkspaceService.runQualityCheck(userId, projectId, body == null ? Map.of() : body));
    }

    @Operation(summary = "保存镜头资产快照")
    @PostMapping("/{projectId}/snapshots")
    public Result<Map<String, Object>> createSnapshot(@PathVariable Long projectId,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(productionWorkspaceService.createSnapshot(userId, projectId, body == null ? Map.of() : body));
    }

    @Operation(summary = "回滚镜头资产快照")
    @PostMapping("/{projectId}/snapshots/{snapshotId}/restore")
    public Result<Map<String, Object>> restoreSnapshot(@PathVariable Long projectId,
                                                       @PathVariable Long snapshotId,
                                                       @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(productionWorkspaceService.restoreSnapshot(userId, projectId, snapshotId));
    }

    @Operation(summary = "生成平台发布包清单")
    @PostMapping("/{projectId}/export-package")
    public Result<Map<String, Object>> exportPackage(@PathVariable Long projectId,
                                                     @RequestBody(required = false) Map<String, Object> body,
                                                     @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(productionWorkspaceService.exportPackage(userId, projectId, body == null ? Map.of() : body));
    }

    @Operation(summary = "保存一致性圣经条目")
    @PutMapping("/{projectId}/bible")
    public Result<Map<String, Object>> upsertBible(@PathVariable Long projectId,
                                                   @RequestBody Map<String, Object> body,
                                                   @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(productionWorkspaceService.upsertBible(userId, projectId, body == null ? Map.of() : body));
    }
}
