package com.niren.drama.dto.script;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScriptSaveRequest {
    private Long id;

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    @NotNull(message = "集数不能为空")
    private Integer episodeNo;

    @NotBlank(message = "剧本标题不能为空")
    private String title;

    @NotBlank(message = "剧本内容不能为空")
    private String content;

    private String aiPrompt;
}