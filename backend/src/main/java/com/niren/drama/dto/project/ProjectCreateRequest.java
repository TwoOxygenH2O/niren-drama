package com.niren.drama.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProjectCreateRequest {
    @NotBlank(message = "项目名称不能为空")
    private String name;
    private String description;
    private String genre;
    @NotNull(message = "剧集数量不能为空")
    private Integer episodes;
    @NotNull(message = "单集时长不能为空")
    private Integer episodeDuration;
}
