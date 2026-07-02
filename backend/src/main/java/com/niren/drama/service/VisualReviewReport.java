package com.niren.drama.service;

import java.util.List;
import java.util.Map;

public record VisualReviewReport(
        boolean analyzed,
        String skippedReason,
        Map<String, Object> metrics,
        List<VisualReviewFinding> findings
) {
    public static VisualReviewReport skipped(String reason) {
        return new VisualReviewReport(false, reason, Map.of(), List.of());
    }
}
