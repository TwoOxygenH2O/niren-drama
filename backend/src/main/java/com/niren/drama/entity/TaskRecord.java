package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_task_record")
public class TaskRecord extends BaseEntity {
    private Long projectId;
    private Long userId;
    /** SCRIPT_GEN | STORYBOARD_GEN | IMAGE_GEN | VIDEO_GEN | AUDIO_GEN | VIDEO_COMPOSE | CHARACTER_GEN | TTS_AUDITION */
    private String taskType;
    /** PENDING | RUNNING | SUCCESS | FAILED */
    private String status;
    /** Progress 0–100 */
    private Integer progress;
    private String message;
    /** JSON result payload */
    private String result;
    private Long refId;

    /** 任务总耗时（毫秒） */
    @TableField(exist = false)
    private Long totalElapsedMs;
    /** 各步骤耗时（毫秒，估算） */
    @TableField(exist = false)
    private Map<String, Long> stepDurationMs;
    /** 失败类型分布（按错误关键词聚合） */
    @TableField(exist = false)
    private Map<String, Integer> failureTypeDistribution;
    /** 外部接口错误占比（0-1） */
    @TableField(exist = false)
    private Double externalApiErrorRatio;
    /** 外部接口总调用数（从 calls 统计） */
    @TableField(exist = false)
    private Integer externalApiCallCount;
}
