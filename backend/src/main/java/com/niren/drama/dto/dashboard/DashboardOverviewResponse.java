package com.niren.drama.dto.dashboard;

import java.util.List;
import java.util.Map;

public record DashboardOverviewResponse(
        ProjectSummary projectSummary,
        TaskSummary taskSummary,
        LatestProject latestProject,
        ProductionSummary productionSummary,
        List<Map<String, Object>> productionStages,
        HealthSummary healthSummary,
        List<Metric> metrics,
        List<RecentRow> recentRows,
        String generatedAt
) {
    public record ProjectSummary(long total, long active, long completed, long failed) {
    }

    public record TaskSummary(
            int total,
            int pending,
            int running,
            int success,
            int failed,
            int active,
            long averageElapsedMs
    ) {
    }

    public record LatestProject(Long id, String name, String status, String updatedAt) {
    }

    public record ProductionSummary(
            int totalShots,
            int firstFrameReady,
            int videoReady,
            int audioReady,
            int issueCount,
            boolean finalReady
    ) {
    }

    public record HealthSummary(long ok, long total, Map<String, Object> items) {
    }

    public record Metric(
            String id,
            String title,
            String status,
            String value,
            String total,
            String description,
            int progress,
            String footerLabel,
            String footerValue,
            String tone
    ) {
    }

    public record RecentRow(
            String scene,
            String project,
            String status,
            String statusTone,
            String progress,
            String detail
    ) {
    }
}
