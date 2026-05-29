package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_asset_snapshot")
public class AssetSnapshot extends BaseEntity {
    private Long projectId;
    private String entityType;
    private Long entityId;
    private String assetType;
    private String content;
    private String assetUrl;
    private String prompt;
    private String provider;
    private String model;
    private String workflowFile;
    private Long sourceTaskId;
    private String parentSnapshotIds;
    private String metadata;
    private Boolean active;
}
