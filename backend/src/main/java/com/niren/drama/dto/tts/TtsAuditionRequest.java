package com.niren.drama.dto.tts;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TtsAuditionRequest {
    private List<Long> characterIds;
    private Boolean includeNarrator = true;
    private Integer candidateCount;
    private String sampleText;
    private Map<String, TtsAuditionRoleOverride> roleOverrides;
}
