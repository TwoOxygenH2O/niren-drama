package com.niren.drama.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectStyleSupportTest {

    @Test
    void defaultsToCostumeRevengeGenre() {
        assertThat(ProjectStyleSupport.resolveGenre(null)).isEqualTo("古装复仇");
        assertThat(ProjectStyleSupport.buildProjectIdentity(null, null)).contains("题材：古装复仇");
    }

    @Test
    void buildsCostumeRevengeSpecificRules() {
        String textRules = ProjectStyleSupport.buildTextCreationRules("真人短剧", "古装复仇");
        String visualRules = ProjectStyleSupport.buildVisualCreationRules("真人短剧", "古装复仇");
        String audioRules = ProjectStyleSupport.buildAudioPerformanceRules("真人短剧", "古装复仇");
        String negativeTerms = ProjectStyleSupport.buildVisualNegativeTerms("真人短剧", "古装复仇");

        assertThat(textRules).contains("古代权谋", "复仇爽点", "强情绪反转");
        assertThat(visualRules).contains("府邸", "身份落差", "禁止现代服饰");
        assertThat(audioRules).contains("压迫感", "清算感");
        assertThat(negativeTerms).contains("现代建筑", "现代发型");
    }
}
