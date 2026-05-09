package com.niren.drama.dto.immersive;

import lombok.Data;

@Data
public class ImmersiveDirectorChatResponse {

    /** 展示在对话区的模型回复 */
    private String reply;

    /**
     * 解析得到的动作：NONE | REGENERATE_STORYBOARD | REGENERATE_SCRIPT | REPAIR_OUTLINE
     */
    private String action;

    /** 异步任务 ID（分镜或剧本生成） */
    private Long taskId;

    /** STORYBOARD_GEN | SCRIPT_GEN 等 */
    private String taskType;

    /** 大纲修复后的正文（仅 REPAIR_OUTLINE 且成功时） */
    private String outlineContent;
}
