package com.niren.drama.dto.scene;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SceneCreateRequest {
    @NotNull(message = "项目ID不能为空")
    private Long projectId;
    @NotBlank(message = "场景名不能为空")
    private String name;
    private String description;
    private String timeOfDay;
    private String location;
}
