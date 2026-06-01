package com.niren.drama.controller;

import com.niren.drama.common.CurrentUserHelper;
import com.niren.drama.common.Result;
import com.niren.drama.service.CasrDemoService;
import com.niren.drama.service.CasrWorkflowService;
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
    private final CasrWorkflowService casrWorkflowService;
    private final CasrDemoService casrDemoService;
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

    @Operation(summary = "运行 CASR 连续性感知诊断")
    @PostMapping("/{projectId}/casr/analyze")
    public Result<Map<String, Object>> analyzeCasr(@PathVariable Long projectId,
                                                   @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(casrWorkflowService.analyze(userId, projectId));
    }

    @Operation(summary = "生成 CASR 自修复策略树")
    @PostMapping("/{projectId}/casr/plan")
    public Result<Map<String, Object>> planCasr(@PathVariable Long projectId,
                                                @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(casrWorkflowService.plan(userId, projectId));
    }

    @Operation(summary = "执行用户确认的 CASR 修复动作")
    @PostMapping("/{projectId}/casr/execute")
    public Result<Map<String, Object>> executeCasr(@PathVariable Long projectId,
                                                   @RequestBody(required = false) Map<String, Object> body,
                                                   @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(casrWorkflowService.execute(userId, projectId, body == null ? Map.of() : body));
    }

    @Operation(summary = "创建 CASR 研究演示项目")
    @PostMapping("/demo/casr")
    public Result<Map<String, Object>> createCasrDemo(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(casrDemoService.createDemo(userId));
    }
}
