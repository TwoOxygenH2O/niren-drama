package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableField;
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
    /** 上屏字幕；空则由对白/旁白与合成配置派生 */
    private String subtitleText;
    /** 配音口播稿；空则派生自旁白+对白 */
    private String ttsText;
    /** 用户已手动锁定上屏，AI 重跑不覆盖 */
    private Boolean userLockedSubtitle;
    /** 用户已手动锁定配音稿，AI 重跑不覆盖 */
    private Boolean userLockedTts;
    @TableField(exist = false)
    private String resolvedSubtitle;
    @TableField(exist = false)
    private String resolvedTts;
    /** Main character featured in this shot */
    private Long characterId;
    private Long sceneId;
    /** Shot duration in seconds */
    private Integer duration;
    private String imageUrl;
    private String videoUrl;
    /** Vendor async video task id */
    private String videoTaskId;
    /** Vendor async video task status url */
    private String videoTaskStatusUrl;
    /** Video provider that accepted the async task */
    private String videoTaskProvider;
    /** submitted | running | success | failed */
    private String videoTaskStatus;
    /** Internal dynamic video batch task id */
    private Long videoTaskRecordId;
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
