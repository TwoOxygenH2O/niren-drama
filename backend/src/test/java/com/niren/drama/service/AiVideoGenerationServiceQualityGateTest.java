package com.niren.drama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.AiResolvedConfig;
import com.niren.drama.ai.impl.ComfyUiVideoProvider;
import com.niren.drama.entity.Character;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.SceneMapper;
import com.niren.drama.mapper.StoryboardMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiVideoGenerationServiceQualityGateTest {

    @Test
    void querySubmittedVideoTaskReturnsFailedWhenGeneratedVideoQualityGateRejectsCompletedVideo() {
        AiProviderFactory aiProviderFactory = mock(AiProviderFactory.class);
        ComfyUiVideoProvider comfyUiVideoProvider = mock(ComfyUiVideoProvider.class);
        GeneratedVideoQualityGate qualityGate = mock(GeneratedVideoQualityGate.class);
        AiVideoGenerationService service = new AiVideoGenerationService(
                aiProviderFactory,
                mock(ProjectService.class),
                mock(CharacterMapper.class),
                mock(SceneMapper.class),
                mock(StoryboardMapper.class),
                mock(PublicAssetStorageService.class),
                new ObjectMapper(),
                qualityGate);

        Storyboard shot = new Storyboard();
        shot.setId(101L);
        shot.setShotNo(1);
        shot.setDuration(8);
        shot.setVideoTaskProvider("comfyui");
        shot.setVideoTaskId("prompt-1");
        shot.setVideoTaskStatusUrl("http://127.0.0.1:8188/history/prompt-1");

        String videoUrl = "http://localhost:8080/api/files/generated-videos/bad.mp4";
        when(aiProviderFactory.resolveConfig(7L, "video")).thenReturn(new AiResolvedConfig(
                "video",
                "comfyui",
                "http://127.0.0.1:8188",
                "",
                "wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors",
                ""));
        when(aiProviderFactory.getVideoProvider(7L)).thenReturn(comfyUiVideoProvider);
        when(comfyUiVideoProvider.queryVideoTask("prompt-1")).thenReturn(
                new ComfyUiVideoProvider.ComfyPromptQueryResult("completed", videoUrl, null));
        when(qualityGate.evaluate(videoUrl, 8)).thenReturn(
                new GeneratedVideoQualityGate.Result(true, false, "视频视觉质检未通过: washed_gray_video", null));

        AiVideoGenerationService.VideoTaskQueryResult result = service.querySubmittedVideoTask(7L, shot);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.videoUrl()).isNull();
        assertThat(result.errorMessage()).contains("washed_gray_video");
    }

    @Test
    void submitVideoTaskBackfillsCharacterFromShotTextBeforeBuildingWanPromptAndReferences() {
        AiProviderFactory aiProviderFactory = mock(AiProviderFactory.class);
        ComfyUiVideoProvider comfyUiVideoProvider = mock(ComfyUiVideoProvider.class);
        CharacterMapper characterMapper = mock(CharacterMapper.class);
        ProjectService projectService = mock(ProjectService.class);
        PublicAssetStorageService publicAssetStorageService = mock(PublicAssetStorageService.class);
        AiVideoGenerationService service = new AiVideoGenerationService(
                aiProviderFactory,
                projectService,
                characterMapper,
                mock(SceneMapper.class),
                mock(StoryboardMapper.class),
                publicAssetStorageService,
                new ObjectMapper(),
                mock(GeneratedVideoQualityGate.class));

        Character heroine = new Character();
        heroine.setId(88L);
        heroine.setProjectId(2L);
        heroine.setName("许知意");
        heroine.setAppearance("黑色利落长发，冷白皮，眼神清冷，白裙外披黑色西装");
        heroine.setImageUrl("http://localhost:8080/api/files/characters/xuzhiyi.png");
        when(characterMapper.selectList(any())).thenReturn(List.of(heroine));
        when(characterMapper.selectById(88L)).thenReturn(heroine);
        when(publicAssetStorageService.ensurePublicUrl(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Project project = new Project();
        project.setId(2L);
        project.setProjectType("真人短剧");
        project.setGenre("都市复仇");
        when(projectService.getProject(2L)).thenReturn(project);
        when(aiProviderFactory.resolveConfig(7L, "video")).thenReturn(new AiResolvedConfig(
                "video",
                "comfyui",
                "http://127.0.0.1:8188",
                "",
                "wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors",
                "{\"maxReferenceImages\":3}"));
        when(aiProviderFactory.getVideoProvider(7L)).thenReturn(comfyUiVideoProvider);
        when(comfyUiVideoProvider.submitVideoFromImageTask(any(), any(), any(), any(Integer.class), any(), any(), any(Boolean.class)))
                .thenReturn(new ComfyUiVideoProvider.ComfyPromptSubmission("prompt-1", "http://127.0.0.1:8188/history/prompt-1"));

        Storyboard shot = new Storyboard();
        shot.setId(101L);
        shot.setProjectId(2L);
        shot.setShotNo(3);
        shot.setDuration(8);
        shot.setImageUrl("http://localhost:8080/api/files/generated-images/shot3.png");
        shot.setDescription("许氏董事会玻璃会议室，许知意把硬盘插入读卡器，大屏亮起证据。");
        shot.setVideoPrompt("许知意把硬盘放在董事会桌面，抬眼看向顾明修。");

        service.submitVideoTask(7L, shot);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> referencesCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(comfyUiVideoProvider).submitVideoFromImageTask(
                any(),
                referencesCaptor.capture(),
                promptCaptor.capture(),
                any(Integer.class),
                any(),
                any(),
                any(Boolean.class));

        assertThat(shot.getCharacterId()).isEqualTo(88L);
        assertThat(promptCaptor.getValue()).contains("Character lock: 许知意");
        assertThat(referencesCaptor.getValue())
                .contains("http://localhost:8080/api/files/generated-images/shot3.png")
                .contains("http://localhost:8080/api/files/characters/xuzhiyi.png");
    }
}
