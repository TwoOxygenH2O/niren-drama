package com.niren.drama.dto.storyboard;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StoryboardPreviewRepairRequest {
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    @NotNull(message = "脚本ID不能为空")
    private Long scriptId;

    @NotBlank(message = "分镜预览内容不能为空")
    private String content;
}