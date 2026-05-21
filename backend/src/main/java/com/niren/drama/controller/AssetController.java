package com.niren.drama.controller;

import com.niren.drama.common.PageQuery;
import com.niren.drama.common.PageResult;
import com.niren.drama.common.Result;
import com.niren.drama.entity.Asset;


import com.niren.drama.service.AssetService;
import com.niren.drama.service.ProjectService;
import com.niren.drama.common.CurrentUserHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;


import java.io.IOException;

@Tag(name = "素材管理", description = "文件上传、素材库管理")
@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;
    private final ProjectService projectService;
    private final CurrentUserHelper currentUserHelper;

    @Operation(summary = "上传文件")
    @PostMapping("/upload")
    public Result<Asset> upload(@RequestParam("file") MultipartFile file,
                                @RequestParam Long projectId,
                                @RequestParam(required = false) String refType,
                                @RequestParam(required = false) Long refId,
                                @AuthenticationPrincipal UserDetails userDetails) throws IOException {
        Long userId = getUserId(userDetails);
        return Result.success(assetService.uploadFile(userId, projectId, file, refType, refId));
    }

    @Operation(summary = "获取项目素材列表")
    @GetMapping("/project/{projectId}")
    public Result<PageResult<Asset>> list(@PathVariable Long projectId,
                                          @RequestParam(required = false) String type,
                                          PageQuery query,
                                          @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        projectService.getProject(userId, projectId); // ownership check
        return Result.success(PageResult.of(assetService.listAssets(projectId, type, query)));
    }

    @Operation(summary = "删除素材")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        Asset asset = assetService.getAsset(id);
        projectService.getProject(userId, asset.getProjectId()); // ownership check
        assetService.deleteAsset(id);
        return Result.success();
    }

    private Long getUserId(UserDetails userDetails) {
        return currentUserHelper.getUserId(userDetails);
    }
}
