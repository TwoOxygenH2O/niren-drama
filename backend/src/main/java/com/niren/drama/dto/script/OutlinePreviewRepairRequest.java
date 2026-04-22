package com.niren.drama.dto.script;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OutlinePreviewRepairRequest {
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    @NotBlank(message = "大纲内容不能为空")
    private String content;

    private String idea;
}