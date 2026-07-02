package com.niren.drama.controller;

import com.niren.drama.common.CurrentUserHelper;
import com.niren.drama.common.Result;
import com.niren.drama.dto.dashboard.DashboardOverviewResponse;
import com.niren.drama.service.DashboardOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "工作台", description = "用户工作台真实数据聚合")
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardOverviewService dashboardOverviewService;
    private final CurrentUserHelper currentUserHelper;

    @Operation(summary = "工作台总览")
    @GetMapping("/overview")
    public Result<DashboardOverviewResponse> overview(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        return Result.success(dashboardOverviewService.getOverview(userId));
    }
}
