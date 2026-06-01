package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_consistency_bible")
public class ConsistencyBible extends BaseEntity {
    private Long projectId;
    private String bibleType;
    private Long refId;
    private String title;
    private String lockedAttributes;
    private String referenceSnapshotIds;
    private String notes;
    private Boolean locked;
}
