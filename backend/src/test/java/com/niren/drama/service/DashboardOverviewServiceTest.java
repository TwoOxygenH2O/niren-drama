package com.niren.drama.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niren.drama.common.PageQuery;
import com.niren.drama.dto.dashboard.DashboardOverviewResponse;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.TaskRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardOverviewServiceTest {

    private final ProjectService projectService = mock(ProjectService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final ProductionWorkspaceService productionWorkspaceService = mock(ProductionWorkspaceService.class);
    private final DashboardOverviewService service = new DashboardOverviewService(
            projectService,
            taskService,
            productionWorkspaceService);

    @Test
    void buildsEmptyOverviewWithoutFallbackFakeNumbers() {
        Page<Project> page = new Page<>(1, 5);
        page.setRecords(List.of());
        page.setTotal(0);
        when(projectService.listProjects(eq(7L), any(PageQuery.class))).thenReturn(page);
        when(taskService.listByUser(7L)).thenReturn(List.of());

        DashboardOverviewResponse overview = service.getOverview(7L);

        assertThat(overview.projectSummary().total()).isZero();
        assertThat(overview.projectSummary().active()).isZero();
        assertThat(overview.projectSummary().completed()).isZero();
        assertThat(overview.projectSummary().failed()).isZero();
        assertThat(overview.taskSummary().total()).isZero();
        assertThat(overview.taskSummary().pending()).isZero();
        assertThat(overview.taskSummary().running()).isZero();
        assertThat(overview.taskSummary().success()).isZero();
        assertThat(overview.taskSummary().failed()).isZero();
        assertThat(overview.taskSummary().active()).isZero();
        assertThat(overview.taskSummary().averageElapsedMs()).isZero();
        assertThat(metricValues(overview))
                .containsExactly("0", "0", "0/0", "0/0", "0/0", "0");
        assertThat(overview.recentRows()).isEqualTo(List.of());
        assertThat(overview.latestProject()).isNull();
    }

    @Test
    void buildsOverviewFromRealProjectTaskAndWorkspaceData() {
        Project project = new Project();
        project.setId(101L);
        project.setName("真实短剧项目");
        project.setStatus("draft");
        project.setUpdateTime(LocalDateTime.parse("2026-06-07T12:30:00"));

        Page<Project> page = new Page<>(1, 5);
        page.setRecords(List.of(project));
        page.setTotal(1);
        when(projectService.listProjects(eq(7L), any(PageQuery.class))).thenReturn(page);

        TaskRecord running = task("VIDEO_GEN", "RUNNING", 35, 20_000L);
        TaskRecord pending = task("IMAGE_GEN", "PENDING", 0, null);
        TaskRecord failed = task("AUDIO_GEN", "FAILED", 100, 6_000L);
        when(taskService.listByUser(7L)).thenReturn(List.of(running, pending, failed));

        Map<String, Object> workspace = workspace();
        when(productionWorkspaceService.getWorkspace(7L, 101L)).thenReturn(workspace);

        DashboardOverviewResponse overview = service.getOverview(7L);

        assertThat(overview.latestProject()).isNotNull();
        assertThat(overview.latestProject().id()).isEqualTo(101L);
        assertThat(overview.latestProject().name()).isEqualTo("真实短剧项目");
        assertThat(overview.latestProject().status()).isEqualTo("draft");
        assertThat(overview.latestProject().updatedAt()).isEqualTo("2026-06-07T12:30");
        assertThat(overview.taskSummary().total()).isEqualTo(3);
        assertThat(overview.taskSummary().pending()).isEqualTo(1);
        assertThat(overview.taskSummary().running()).isEqualTo(1);
        assertThat(overview.taskSummary().success()).isZero();
        assertThat(overview.taskSummary().failed()).isEqualTo(1);
        assertThat(overview.taskSummary().active()).isEqualTo(2);
        assertThat(overview.taskSummary().averageElapsedMs()).isEqualTo(13_000L);
        assertThat(overview.productionSummary().totalShots()).isEqualTo(7);
        assertThat(overview.productionSummary().firstFrameReady()).isEqualTo(6);
        assertThat(overview.productionSummary().videoReady()).isEqualTo(4);
        assertThat(overview.productionSummary().audioReady()).isZero();
        assertThat(overview.productionSummary().issueCount()).isEqualTo(8);
        assertThat(overview.productionSummary().finalReady()).isFalse();
        assertThat(metricValues(overview))
                .containsExactly("2", "1", "6/7", "4/7", "2/3", "8");
        assertThat(overview.recentRows()).hasSize(3);
        DashboardOverviewResponse.RecentRow firstRow = overview.recentRows().get(0);
        assertThat(firstRow.scene()).isEqualTo("雨夜巷口发现证据");
        assertThat(firstRow.project()).isEqualTo("真实短剧项目");
        assertThat(firstRow.progress()).isEqualTo("33%");
        assertThat(firstRow.statusTone()).isEqualTo("cyan");
    }

    private List<String> metricValues(DashboardOverviewResponse overview) {
        List<Object> values = new ArrayList<>();
        for (DashboardOverviewResponse.Metric item : overview.metrics()) {
            values.add(item.value());
        }
        return values.stream().map(String::valueOf).toList();
    }

    private TaskRecord task(String type, String status, Integer progress, Long elapsedMs) {
        TaskRecord task = new TaskRecord();
        task.setTaskType(type);
        task.setStatus(status);
        task.setProgress(progress);
        task.setTotalElapsedMs(elapsedMs);
        return task;
    }

    private Map<String, Object> workspace() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalShots", 7);
        summary.put("firstFrameReady", 6);
        summary.put("videoReady", 4);
        summary.put("audioReady", 0);
        summary.put("issueCount", 8);
        summary.put("finalReady", false);

        Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("summary", summary);
        completion.put("stages", List.of(
                Map.of("id", "firstFrame", "label", "首帧", "ready", 6, "total", 7, "percent", 86),
                Map.of("id", "video", "label", "视频", "ready", 4, "total", 7, "percent", 57),
                Map.of("id", "audio", "label", "配音", "ready", 0, "total", 7, "percent", 0)
        ));

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("token", Map.of("status", "ok", "label", "登录有效"));
        health.put("ffmpeg", Map.of("status", "ok", "label", "FFmpeg 可用"));
        health.put("videoConfig", Map.of("status", "degraded", "label", "视频工作流未配置"));

        return Map.of(
                "completion", completion,
                "health", health,
                "shots", List.of(
                        shot("雨夜巷口发现证据", "等待 ComfyUI", "首帧已就绪", "待配音", 0),
                        shot("天台对峙", "视频已就绪", "首帧已就绪", "待配音", 0),
                        shot("雨声压住尾声", "生成失败", "首帧已就绪", "待配音", 1)
                )
        );
    }

    private Map<String, Object> shot(String description, String video, String firstFrame, String audio, int issueCount) {
        return Map.of(
                "description", description,
                "status", Map.of("video", video, "firstFrame", firstFrame, "audio", audio),
                "quality", Map.of("issueCount", issueCount)
        );
    }
}
