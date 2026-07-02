package com.niren.drama.common;

import com.niren.drama.entity.Project;
import com.niren.drama.entity.Storyboard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Wan22ShortDramaPromptBuilderTest {

    @Test
    void activeShotOverridesStaleLowMotionLevel() {
        Storyboard shot = new Storyboard();
        shot.setMotionLevel("low");
        shot.setVideoPrompt("镜头缓慢推进，女主猛然睁眼，指尖掐入圣旨，胸口起伏带动嫁衣褶皱，烛火晃动");
        shot.setNarration("她从死局归来，第一眼便认清了仇人。");

        Project project = new Project();
        project.setProjectType("真人短剧");
        project.setGenre("古装复仇");

        String prompt = Wan22ShortDramaPromptBuilder.build(shot, null, null, project, false);

        assertThat(prompt).contains("Motion intensity: medium");
        assertThat(prompt).doesNotContain("Motion intensity: low");
        assertThat(prompt).contains("visible actor-local action progression");
        assertThat(prompt).contains("keep the background anchored");
        assertThat(prompt).contains("no whole-frame pan");
        assertThat(prompt).contains("no gif-like zoom");
        assertThat(prompt).doesNotContain("slow push-in with slight handheld parallax");
    }

    @Test
    void sanitizesCameraPushLanguageInsideActionBeat() {
        Storyboard shot = new Storyboard();
        shot.setVideoPrompt("缓推镜头，从信封上缓慢推进至女主面部，随后镜头下移到她握紧的手。");

        Project project = new Project();
        project.setProjectType("真人短剧");
        project.setGenre("古装复仇");

        String prompt = Wan22ShortDramaPromptBuilder.build(shot, null, null, project, false);

        assertThat(prompt)
                .contains("Action beat:")
                .contains("锁机位表演镜头")
                .contains("通过人物动作和景深变化呈现")
                .contains("演员低头或手部动作进入画面")
                .contains("Keep the camera locked or nearly locked");
        assertThat(prompt)
                .doesNotContain("缓推镜头")
                .doesNotContain("缓慢推进")
                .doesNotContain("镜头下移");
    }

    @Test
    void includesEpisodeContinuityBibleForCrossShotStyleLock() {
        Storyboard shot = new Storyboard();
        shot.setVideoPrompt("许知意把硬盘放在董事会桌面，抬眼看向顾明修。");

        Project project = new Project();
        project.setProjectType("真人短剧");
        project.setGenre("都市复仇");

        String prompt = Wan22ShortDramaPromptBuilder.build(shot, null, null, project, false);

        assertThat(prompt)
                .contains("Episode continuity bible")
                .contains("same drama episode")
                .contains("consistent actor identity")
                .contains("same color grade");
    }
}
