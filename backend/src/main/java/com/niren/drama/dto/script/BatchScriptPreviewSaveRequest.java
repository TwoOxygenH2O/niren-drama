package com.niren.drama.dto.script;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BatchScriptPreviewSaveRequest {
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    @NotNull(message = "起始集不能为空")
    private Integer startEpisode;

    @NotNull(message = "结束集不能为空")
    private Integer endEpisode;

    @NotBlank(message = "批量剧本内容不能为空")
    private String content;

    private String idea;
}