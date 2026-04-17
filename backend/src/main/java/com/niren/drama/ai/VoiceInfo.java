package com.niren.drama.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VoiceInfo {
    private String voiceId;
    private String name;
    private String gender;
    private String language;
    private String description;
}
