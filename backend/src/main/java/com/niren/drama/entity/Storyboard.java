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
    /** Video generation prompt */
    private String videoPrompt;
    /** low | medium | high */
    private String motionLevel;
    /** System recommendation for dynamic shot */
    private Boolean dynamicRecommended;
    /** Final user-selected dynamic shot */
    private Boolean dynamicSelected;
    /** Recommendation score 0-100 */
    private Integer dynamicScore;
    /** Recommendation reason for UI display */
    private String dynamicReason;
    /** image | video */
    private String renderMode;
    /** draft | image_generated | video_generated | audio_generated | completed */
    private String status;
}
