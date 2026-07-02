package com.niren.drama.service;

import java.util.List;
import java.util.Map;

public record VisualQualityReport(boolean analyzed,
                                  Map<String, Object> metrics,
                                  List<VisualQualityFinding> findings) {

    public static VisualQualityReport skipped(String reason) {
        return new VisualQualityReport(false, Map.of("reason", reason), List.of());
    }
}
