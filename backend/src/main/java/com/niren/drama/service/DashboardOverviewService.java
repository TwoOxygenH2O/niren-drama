package com.niren.drama.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niren.drama.common.PageQuery;
import com.niren.drama.dto.dashboard.DashboardOverviewResponse;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.TaskRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DashboardOverviewService {

    private static final List<String> ACTIVE_TASK_STATUSES = List.of("PENDING", "RUNNING");
    private static final int MAX_RECENT_ROWS = 6;

    private final ProjectService projectService;
    private final TaskService taskService;
    private final ProductionWorkspaceService productionWorkspaceService;

    public DashboardOverviewResponse getOverview(Long userId) {
        Page<Project> projectsPage = listRecentProjects(userId);
        List<Project> projects = projectsPage.getRecords() == null ? List.of() : projectsPage.getRecords();
        List<TaskRecord> tasks = taskService.listByUser(userId);
        Project latestProject = projects.isEmpty() ? null : projects.get(0);
        Map<String, Object> workspace = latestProject == null
                ? Map.of()
                : productionWorkspaceService.getWorkspace(userId, latestProject.getId());

        return new DashboardOverviewResponse(
                buildProjectSummary(projectsPage.getTotal(), projects),
                buildTaskSummary(tasks),
                latestProject == null ? null : latestProjectSummary(latestProject),
                buildProductionSummary(workspace),
                readStages(workspace),
                buildHealthSummary(workspace),
                buildMetrics(tasks, workspace),
                buildRecentRows(latestProject, workspace, tasks),
                LocalDateTime.now().toString()
        );
    }

    private Page<Project> listRecentProjects(Long userId) {
        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(100);
        return projectService.listProjects(userId, query);
    }

    private DashboardOverviewResponse.ProjectSummary buildProjectSummary(long total, List<Project> projects) {
        long completed = projects.stream().filter(project -> hasStatus(project.getStatus(), "completed")).count();
        long failed = projects.stream().filter(project -> hasStatus(project.getStatus(), "failed")).count();
        long active = Math.max(0, total - completed - failed);
        return new DashboardOverviewResponse.ProjectSummary(total, active, completed, failed);
    }

    private DashboardOverviewResponse.TaskSummary buildTaskSummary(List<TaskRecord> tasks) {
        int pending = countTasks(tasks, "PENDING");
        int running = countTasks(tasks, "RUNNING");
        int success = countTasks(tasks, "SUCCESS");
        int failed = countTasks(tasks, "FAILED");
        long averageElapsed = Math.round(tasks.stream()
                .map(TaskRecord::getTotalElapsedMs)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .average()
                .orElse(0d));
        return new DashboardOverviewResponse.TaskSummary(
                tasks.size(),
                pending,
                running,
                success,
                failed,
                pending + running,
                averageElapsed
        );
    }

    private int countTasks(List<TaskRecord> tasks, String status) {
        return (int) tasks.stream().filter(task -> hasStatus(task.getStatus(), status)).count();
    }

    private DashboardOverviewResponse.LatestProject latestProjectSummary(Project project) {
        return new DashboardOverviewResponse.LatestProject(
                project.getId(),
                project.getName(),
                project.getStatus(),
                project.getUpdateTime() == null ? "" : project.getUpdateTime().toString()
        );
    }

    private DashboardOverviewResponse.ProductionSummary buildProductionSummary(Map<String, Object> workspace) {
        Map<String, Object> summary = readMap(readMap(workspace.get("completion")).get("summary"));
        return new DashboardOverviewResponse.ProductionSummary(
                asInt(summary.get("totalShots")),
                asInt(summary.get("firstFrameReady")),
                asInt(summary.get("videoReady")),
                asInt(summary.get("audioReady")),
                asInt(summary.get("issueCount")),
                Boolean.TRUE.equals(summary.get("finalReady"))
        );
    }

    private List<Map<String, Object>> readStages(Map<String, Object> workspace) {
        Object stages = readMap(workspace.get("completion")).get("stages");
        return stages instanceof List<?> list
                ? list.stream().map(DashboardOverviewService::readMap).toList()
                : List.of();
    }

    private DashboardOverviewResponse.HealthSummary buildHealthSummary(Map<String, Object> workspace) {
        Map<String, Object> health = readMap(workspace.get("health"));
        long total = health.size();
        long ok = health.values().stream()
                .map(DashboardOverviewService::readMap)
                .filter(item -> hasStatus(String.valueOf(item.get("status")), "ok"))
                .count();
        return new DashboardOverviewResponse.HealthSummary(ok, total, health);
    }

    private List<DashboardOverviewResponse.Metric> buildMetrics(List<TaskRecord> tasks, Map<String, Object> workspace) {
        DashboardOverviewResponse.TaskSummary taskSummary = buildTaskSummary(tasks);
        DashboardOverviewResponse.ProductionSummary production = buildProductionSummary(workspace);
        DashboardOverviewResponse.HealthSummary health = buildHealthSummary(workspace);

        int totalShots = production.totalShots();
        int firstFrameReady = production.firstFrameReady();
        int videoReady = production.videoReady();
        int issueCount = production.issueCount();
        int activeTasks = taskSummary.active();
        int pendingTasks = taskSummary.pending();
        int runningTasks = taskSummary.running();
        int failedTasks = taskSummary.failed();
        int healthOk = Math.toIntExact(health.ok());
        int healthTotal = Math.toIntExact(health.total());

        return List.of(
                metric("activeTasks", "活跃生成", activeTasks > 0 ? "运行中" : "空闲",
                        String.valueOf(activeTasks), String.valueOf(taskSummary.total()),
                        runningTasks + " 个运行中 · " + pendingTasks + " 个等待",
                        percent(activeTasks, Math.max(1, taskSummary.total())),
                        "失败任务", String.valueOf(failedTasks), "default"),
                metric("queue", "队列状态", pendingTasks > 0 ? "等待中" : "无排队",
                        String.valueOf(pendingTasks), String.valueOf(activeTasks),
                        "排队 " + pendingTasks + " 个 · 运行 " + runningTasks + " 个",
                        percent(pendingTasks, Math.max(1, activeTasks)),
                        "平均耗时", formatDuration(taskSummary.averageElapsedMs()), "violet"),
                metric("firstFrames", "首帧就绪", firstFrameReady > 0 ? "生产中" : "待开始",
                        firstFrameReady + "/" + totalShots, "",
                        totalShots == 0 ? "暂无镜头" : firstFrameReady + " 个首帧可用于图生视频",
                        percent(firstFrameReady, totalShots),
                        "缺失首帧", String.valueOf(Math.max(0, totalShots - firstFrameReady)), "default"),
                metric("videoReady", "视频镜头", videoReady == totalShots && totalShots > 0 ? "已完成" : "进行中",
                        videoReady + "/" + totalShots, "",
                        totalShots == 0 ? "暂无镜头" : videoReady + " 个镜头已有视频",
                        percent(videoReady, totalShots),
                        "完成率", percent(videoReady, totalShots) + "%", "default"),
                metric("environment", "生产环境", healthTotal == 0 ? "未检测" : healthOk == healthTotal ? "健康" : "需配置",
                        healthOk + "/" + healthTotal, "",
                        healthTotal == 0 ? "暂无项目可检测" : healthOk + " 项可用 · " + Math.max(0, healthTotal - healthOk) + " 项需处理",
                        percent(healthOk, healthTotal),
                        "检查项", String.valueOf(healthTotal), "default"),
                metric("issues", "待处理问题", issueCount > 0 ? "待修复" : "清爽",
                        String.valueOf(issueCount), "",
                        issueCount > 0 ? issueCount + " 个问题会影响发布" : "当前项目没有待处理问题",
                        issueCount == 0 ? 100 : Math.max(0, 100 - issueCount * 10),
                        "关联镜头", String.valueOf(totalShots), "review")
        );
    }

    private DashboardOverviewResponse.Metric metric(String id, String title, String status, String value, String total,
                                                    String description, int progress, String footerLabel,
                                                    String footerValue, String tone) {
        return new DashboardOverviewResponse.Metric(
                id,
                title,
                status,
                value,
                total,
                description,
                progress,
                footerLabel,
                footerValue,
                tone
        );
    }

    private List<DashboardOverviewResponse.RecentRow> buildRecentRows(
            Project latestProject,
            Map<String, Object> workspace,
            List<TaskRecord> tasks
    ) {
        List<?> shots = readList(workspace.get("shots"));
        if (latestProject != null && !shots.isEmpty()) {
            return shots.stream()
                    .limit(MAX_RECENT_ROWS)
                    .map(DashboardOverviewService::readMap)
                    .map(shot -> shotRow(latestProject, shot))
                    .toList();
        }
        return tasks.stream()
                .limit(MAX_RECENT_ROWS)
                .map(this::taskRow)
                .toList();
    }

    private DashboardOverviewResponse.RecentRow shotRow(Project project, Map<String, Object> shot) {
        Map<String, Object> status = readMap(shot.get("status"));
        String video = string(status.get("video"));
        String firstFrame = string(status.get("firstFrame"));
        String audio = string(status.get("audio"));
        int issueCount = asInt(readMap(shot.get("quality")).get("issueCount"));
        int progress = 0;
        progress += isReady(firstFrame) ? 33 : 0;
        progress += isReady(video) ? 34 : 0;
        progress += isReady(audio) ? 33 : 0;
        String statusText = !video.isBlank() ? video : (!firstFrame.isBlank() ? firstFrame : audio);

        return new DashboardOverviewResponse.RecentRow(
                string(shot.get("description")),
                project.getName(),
                statusText.isBlank() ? "暂无状态" : statusText,
                toneForStatus(statusText, issueCount),
                Math.min(100, progress) + "%",
                issueCount > 0 ? issueCount + " 个问题" : "无问题"
        );
    }

    private DashboardOverviewResponse.RecentRow taskRow(TaskRecord task) {
        String rawStatus = task.getStatus();
        return new DashboardOverviewResponse.RecentRow(
                labelTaskType(task.getTaskType()),
                task.getProjectId() == null ? "未关联项目" : String.valueOf(task.getProjectId()),
                labelTaskStatus(rawStatus),
                toneForStatus(rawStatus, "FAILED".equalsIgnoreCase(rawStatus) ? 1 : 0),
                (task.getProgress() == null ? 0 : task.getProgress()) + "%",
                formatDuration(task.getTotalElapsedMs())
        );
    }

    private String labelTaskType(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return "生产任务";
        }
        return switch (taskType) {
            case "SCRIPT_GEN" -> "剧本生成";
            case "STORYBOARD_GEN" -> "分镜生成";
            case "IMAGE_GEN" -> "首帧生成";
            case "VIDEO_GEN", "DYNAMIC_VIDEO_GEN" -> "图生视频";
            case "AUDIO_GEN" -> "配音生成";
            case "VIDEO_COMPOSE" -> "视频合成";
            default -> taskType;
        };
    }

    private String labelTaskStatus(String status) {
        if (status == null || status.isBlank()) {
            return "暂无状态";
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "PENDING" -> "等待中";
            case "RUNNING" -> "生成中";
            case "SUCCESS" -> "已完成";
            case "FAILED" -> "生成失败";
            default -> status;
        };
    }

    private static Map<String, Object> readMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private List<?> readList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static boolean hasStatus(String actual, String expected) {
        return actual != null && actual.equalsIgnoreCase(expected);
    }

    private boolean isReady(String status) {
        return status != null && (status.contains("已就绪") || status.contains("已完成") || status.contains("SUCCESS"));
    }

    private String toneForStatus(String status, int issueCount) {
        String raw = status == null ? "" : status;
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (issueCount > 0 || normalized.contains("fail") || raw.contains("失败") || raw.contains("修复")) {
            return "red";
        }
        if (normalized.contains("running") || normalized.contains("pending") || status.contains("等待") || status.contains("生成")) {
            return "cyan";
        }
        if (normalized.contains("success") || status.contains("就绪") || status.contains("完成")) {
            return "green";
        }
        return "violet";
    }

    private int percent(int value, int total) {
        if (total <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, Math.round(value * 100f / total)));
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String formatDuration(Long elapsedMs) {
        if (elapsedMs == null || elapsedMs <= 0) {
            return "00:00";
        }
        long totalSeconds = elapsedMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }
}
