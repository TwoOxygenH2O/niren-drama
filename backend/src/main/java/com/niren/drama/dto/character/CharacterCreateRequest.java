package com.niren.drama.dto.character;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CharacterCreateRequest {
    @NotNull(message = "项目ID不能为空")
    private Long projectId;
    @NotBlank(message = "角色名不能为空")
    private String name;
    private String description;
    private String personality;
    private String appearance;
    private String gender;
    private String age;
    private String voiceId;
    private String voiceName;
    /** 语速，100=1.0x，可空 */
    private Integer speechRate;
    /** 合并进 TTS 导演说明 */
    private String ttsNote;
}
