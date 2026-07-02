package com.niren.drama.dto.tts;

import lombok.Data;

import java.util.List;

@Data
public class TtsAuditionRoleOverride {
    private String speakerReferenceAudioUrl;
    private String emotionReferenceAudioUrl;
    private String emotionText;
    private List<Double> emotionVector;
    private Double speed;
}
