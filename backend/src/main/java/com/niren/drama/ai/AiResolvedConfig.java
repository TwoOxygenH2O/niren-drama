package com.niren.drama.ai;

public record AiResolvedConfig(
        String configType,
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        String extra
) {
}