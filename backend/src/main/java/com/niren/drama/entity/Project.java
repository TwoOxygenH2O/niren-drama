package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_project")
public class Project extends BaseEntity {
    private Long userId;
    private String name;
    private String description;
    /** romance, fantasy, thriller, urban, historical, etc. */
    private String genre;
    private Integer episodes;
    /** Episode duration in seconds */
    private Integer episodeDuration;
    /** draft | generating | completed | failed */
    private String status;
    private String coverImage;
}
