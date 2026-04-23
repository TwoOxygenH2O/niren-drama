package com.niren.drama.dto.video;

import lombok.Data;

@Data
public class ReferenceVideoTaskStatusResponse {
    private String provider;
    private String taskId;
    private String statusUrl;
    private String status;
    private String videoUrl;
    private String errorMessage;
}