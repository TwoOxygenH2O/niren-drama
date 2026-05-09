package com.niren.drama.dto.immersive;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ImmersiveDirectorChatRequest {

    /** 由路径填充，请求体可省略 */
    private Long projectId;

    /** 用户在输入框中的自然语言指令 */
    @NotBlank(message = "消息不能为空")
    private String message;

    /** 当前选中的剧集序号，默认 1 */
    private Integer episodeNo;

    /**
     * 前端创作阶段：outline | script_gen | plan_ready
     */
    private String workflowPhase;

    /** 大纲阶段须带上当前大纲全文（可能较长） */
    private String outlineContent;
}
