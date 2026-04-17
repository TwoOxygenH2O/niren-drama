package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_scene")
public class Scene extends BaseEntity {
    private Long projectId;
    private String name;
    private String description;
    /** day | night | dawn | dusk */
    private String timeOfDay;
    /** indoor | outdoor */
    private String location;
    private String imageUrl;
    private Integer sortOrder;
}
