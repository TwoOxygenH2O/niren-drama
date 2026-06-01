package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_casr_run")
public class CasrRun extends BaseEntity {
    private Long projectId;
    private Long userId;
    private String runType;
    private Integer qualityScore;
    private Integer continuityScore;
    private Integer overallScore;
    private String failureTypes;
    private String analysisJson;
    private String planJson;
    private String recommendedAction;
    private Double estimatedCost;
    private Double estimatedSavings;
    private String status;
}
