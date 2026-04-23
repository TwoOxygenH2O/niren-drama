package com.niren.drama.ai.trace;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AiTraceSupport {

    private static final int MAX_BODY_LENGTH = 4000;

    private AiTraceSupport() {
    }

    public static Map<String, String> jsonHeaders(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (hasText(apiKey)) {
            headers.put("Authorization", maskBearer(apiKey));
        }
        return headers;
    }

    public static void record(
            String configType,
            String provider,
            String action,
            String method,
            String url,
            Map<String, String> requestHeaders,
            String requestBody,
            Integer statusCode,
            String responseContentType,
            String responseBody,
            Integer responseBytes,
            boolean success,
            String outputUrl,
            String error) {
        AiTraceContext.record(AiCallTrace.builder()
                .configType(configType)
                .provider(provider)
                .action(action)
                .method(method)
                .url(url)
                .requestHeaders(requestHeaders)
                .requestBody(truncate(requestBody))
                .statusCode(statusCode)
                .responseContentType(responseContentType)
                .responseBody(truncate(responseBody))
                .responseBytes(responseBytes)
                .success(success)
                .outputUrl(outputUrl)
                .error(truncate(error))
                .build());
    }

    public static String maskBearer(String apiKey) {
        if (!hasText(apiKey)) {
            return "";
        }
        String trimmed = apiKey.trim();
        int visible = Math.min(4, trimmed.length());
        return "Bearer ***" + trimmed.substring(trimmed.length() - visible);
    }

    public static String truncate(String text) {
        if (!hasText(text) || text.length() <= MAX_BODY_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_BODY_LENGTH) + "...(truncated)";
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}