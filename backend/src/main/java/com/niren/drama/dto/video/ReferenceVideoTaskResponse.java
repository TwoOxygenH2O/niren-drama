package com.niren.drama.dto.video;

import lombok.Data;

@Data
public class ReferenceVideoTaskResponse {
    private String provider;
    private String model;
    private String prompt;
    private String referenceImageUrl;
    private String taskId;
    private String statusUrl;
    private String videoUrl;
}