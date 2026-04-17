package com.niren.drama.dto.storyboard;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StoryboardGenerateRequest {
    @NotNull(message = "脚本ID不能为空")
    private Long scriptId;
    @NotNull(message = "项目ID不能为空")
    private Long projectId;
}
