package com.niren.drama.dto.script;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScriptGenerateRequest {
    @NotNull(message = "项目ID不能为空")
    private Long projectId;
    private String idea;
    private Integer episodeNo;
    /** Inclusive generation range start episode */
    private Integer startEpisode;
    /** Inclusive generation range end episode */
    private Integer endEpisode;
    /** Total number of episodes for the series (used for outline generation) */
    private Integer totalEpisodes;
    private String genre;
    private String style;
}
