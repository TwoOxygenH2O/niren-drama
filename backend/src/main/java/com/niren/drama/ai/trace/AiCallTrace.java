package com.niren.drama.ai.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiCallTrace {
    private String configType;
    private String provider;
    private String action;
    private String method;
    private String url;
    private Map<String, String> requestHeaders;
    private String requestBody;
    private Integer statusCode;
    private String responseContentType;
    private String responseBody;
    private Integer responseBytes;
    private Boolean success;
    private String outputUrl;
    private String error;
}