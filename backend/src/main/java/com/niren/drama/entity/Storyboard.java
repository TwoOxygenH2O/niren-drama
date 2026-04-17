package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_storyboard")
public class Storyboard extends BaseEntity {
    private Long projectId;
    private Long scriptId;
    private Integer episodeNo;
    private Integer shotNo;
    /** Shot description / visual notes */
    private String description;
    /** close-up | medium | wide | overhead | pov */
    private String cameraAngle;
    /** Character dialogue lines */
    private String dialogue;
    /** Narrator text */
    private String narration;
    /** Main character featured in this shot */
    private Long characterId;
    private Long sceneId;
    /** Shot duration in seconds */
    private Integer duration;
    private String imageUrl;
    private String videoUrl;
    private String audioUrl;
    /** Image generation prompt */
    private String imagePrompt;
    /** draft | image_generated | video_generated | audio_generated | completed */
    private String status;
}
