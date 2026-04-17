package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_asset")
public class Asset extends BaseEntity {
    private Long projectId;
    private Long userId;
    private String name;
    /** image | video | audio */
    private String type;
    private String url;
    private String filePath;
    private Long fileSize;
    private String mimeType;
    /** character | scene | storyboard | final */
    private String refType;
    private Long refId;
}
