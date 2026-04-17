package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_script")
public class Script extends BaseEntity {
    private Long projectId;
    private Integer episodeNo;
    private String title;
    /** Full script text content */
    private String content;
    private String summary;
    /** draft | ai_generated | reviewed */
    private String status;
    /** Prompt or idea used for generation */
    private String aiPrompt;
}
