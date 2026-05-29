package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_production_issue")
public class ProductionIssue extends BaseEntity {
    private Long projectId;
    private Long shotId;
    private String issueType;
    private String severity;
    private String status;
    private String title;
    private String message;
    private String recommendedAction;
    private String actions;
    private String metadata;
}
