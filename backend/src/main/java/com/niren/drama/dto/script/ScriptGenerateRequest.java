package com.niren.drama.dto.script;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScriptGenerateRequest {
    @NotNull(message = "项目ID不能为空")
    private Long projectId;
    @NotBlank(message = "创意描述不能为空")
    private String idea;
    private Integer episodeNo;
    private String genre;
    private String style;
}
