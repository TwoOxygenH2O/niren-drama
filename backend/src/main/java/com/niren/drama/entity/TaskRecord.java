package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_task_record")
public class TaskRecord extends BaseEntity {
    private Long projectId;
    private Long userId;
    /** SCRIPT_GEN | STORYBOARD_GEN | IMAGE_GEN | VIDEO_GEN | AUDIO_GEN | VIDEO_COMPOSE | CHARACTER_GEN */
    private String taskType;
    /** PENDING | RUNNING | SUCCESS | FAILED */
    private String status;
    /** Progress 0–100 */
    private Integer progress;
    private String message;
    /** JSON result payload */
    private String result;
    private Long refId;
}
