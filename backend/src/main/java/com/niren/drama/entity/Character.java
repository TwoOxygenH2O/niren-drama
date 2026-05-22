package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_character")
public class Character extends BaseEntity {
    private Long projectId;
    private String name;
    private String description;
    private String personality;
    private String appearance;
    /** male | female | other */
    private String gender;
    private String age;
    private String imageUrl;
    private String imageUrls;
    /** TTS voice identifier */
    private String voiceId;
    private String voiceName;
    /** TTS 语速，100=1.0x，可空用默认 1.0 */
    private Integer speechRate;
    /** 导演补充，合并进 TTS instruction */
    private String ttsNote;
    private Integer sortOrder;
}
