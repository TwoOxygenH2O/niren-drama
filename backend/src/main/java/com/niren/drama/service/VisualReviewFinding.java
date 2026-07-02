package com.niren.drama.service;

import java.util.Map;

public record VisualReviewFinding(
        String issueType,
        String severity,
        String title,
        String message,
        String recommendedAction,
        Map<String, Object> metadata
) {
}
