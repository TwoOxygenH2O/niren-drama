package com.niren.drama.controller;

import com.niren.drama.common.Result;
import com.niren.drama.service.CostEstimationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "成本预估", description = "API调用成本预估与优化建议")
@RestController
@RequestMapping("/cost")
@RequiredArgsConstructor
public class CostEstimationController {

    private final CostEstimationService costEstimationService;

    @Operation(summary = "预估系列短剧总成本")
    @GetMapping("/estimate")
    public Result<CostEstimationService.CostEstimation> estimateCost(
            @RequestParam(defaultValue = "50") int episodes,
            @RequestParam(defaultValue = "480") int episodeDuration,
            @RequestParam(defaultValue = "0") int shotsPerEpisode) {
        return Result.success(costEstimationService.estimateSeriesCost(episodes, episodeDuration, shotsPerEpisode));
    }

    @Operation(summary = "获取镜头最优图片分辨率")
    @GetMapping("/optimal-image-size")
    public Result<String> getOptimalImageSize(
            @RequestParam(defaultValue = "medium") String cameraAngle) {
        return Result.success(costEstimationService.getOptimalImageSize(cameraAngle));
    }
}
