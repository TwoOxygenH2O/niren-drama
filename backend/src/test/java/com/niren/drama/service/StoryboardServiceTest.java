package com.niren.drama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.ImageAiProvider;
import com.niren.drama.entity.Character;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.SceneMapper;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.StoryboardMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryboardServiceTest {

    @Test
    void ensureShotsHaveImagesUsesPortraitReferenceSizeForDynamicVideoFirstFrame() {
        StoryboardMapper storyboardMapper = mock(StoryboardMapper.class);
        ScriptMapper scriptMapper = mock(ScriptMapper.class);
        TaskRecordMapper taskRecordMapper = mock(TaskRecordMapper.class);
        CharacterMapper characterMapper = mock(CharacterMapper.class);
        SceneMapper sceneMapper = mock(SceneMapper.class);
        AiProviderFactory aiProviderFactory = mock(AiProviderFactory.class);
        ProjectService projectService = mock(ProjectService.class);
        CostEstimationService costEstimationService = mock(CostEstimationService.class);
        PublicAssetStorageService publicAssetStorageService = mock(PublicAssetStorageService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StoryboardService> selfProvider = mock(ObjectProvider.class);
        ImageAiProvider imageProvider = mock(ImageAiProvider.class);

        when(aiProviderFactory.getImageProvider(1L)).thenReturn(imageProvider);
        when(projectService.getProject(1L, 2L)).thenReturn(project());
        when(costEstimationService.getOptimalImageSize("close-up")).thenReturn("1024x1792");
        when(imageProvider.generateImage(anyString(), anyString(), anyString(), anyList(), any()))
                .thenReturn("http://localhost:8080/api/files/generated-images/frame.png");

        StoryboardService service = new StoryboardService(
                storyboardMapper,
                scriptMapper,
                taskRecordMapper,
                characterMapper,
                sceneMapper,
                aiProviderFactory,
                projectService,
                costEstimationService,
                publicAssetStorageService,
                new ObjectMapper(),
                selfProvider);

        Storyboard shot = new Storyboard();
        shot.setId(10L);
        shot.setProjectId(2L);
        shot.setShotNo(1);
        shot.setCameraAngle("close-up");
        shot.setImagePrompt("竖版9:16构图，女主烛火前猛然睁眼。");

        service.ensureShotsHaveImages(1L, 2L, List.of(shot));

        verify(imageProvider).generateImage(anyString(), eq("1024x1792"), eq("vivid"), anyList(), any());
        assertThat(shot.getImageUrl()).contains("frame.png");
        assertThat(shot.getStatus()).isEqualTo("image_generated");
    }

    @Test
    void ensureShotsHaveImagesBackfillsCharacterFromShotTextBeforePrompting() {
        StoryboardMapper storyboardMapper = mock(StoryboardMapper.class);
        ScriptMapper scriptMapper = mock(ScriptMapper.class);
        TaskRecordMapper taskRecordMapper = mock(TaskRecordMapper.class);
        CharacterMapper characterMapper = mock(CharacterMapper.class);
        SceneMapper sceneMapper = mock(SceneMapper.class);
        AiProviderFactory aiProviderFactory = mock(AiProviderFactory.class);
        ProjectService projectService = mock(ProjectService.class);
        CostEstimationService costEstimationService = mock(CostEstimationService.class);
        PublicAssetStorageService publicAssetStorageService = mock(PublicAssetStorageService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StoryboardService> selfProvider = mock(ObjectProvider.class);
        ImageAiProvider imageProvider = mock(ImageAiProvider.class);

        Character heroine = new Character();
        heroine.setId(88L);
        heroine.setProjectId(2L);
        heroine.setName("许知意");
        heroine.setGender("female");
        heroine.setAge("27岁");
        heroine.setAppearance("黑色利落长发，冷白皮，眼神清冷，白裙外披黑色西装");
        heroine.setImageUrl("http://localhost:8080/api/files/characters/xuzhiyi.png");

        when(aiProviderFactory.getImageProvider(1L)).thenReturn(imageProvider);
        when(projectService.getProject(1L, 2L)).thenReturn(project());
        when(characterMapper.selectList(any())).thenReturn(List.of(heroine));
        when(costEstimationService.getOptimalImageSize("wide")).thenReturn("1024x1792");
        when(imageProvider.generateImage(
                argThat(prompt -> prompt != null
                        && prompt.contains("Heroine identity lock")
                        && prompt.contains("黑色利落长发")
                        && prompt.contains("clean single-scene composition")
                        && !prompt.contains("no character sheet")),
                eq("1024x1792"),
                eq("vivid"),
                anyList(),
                any()))
                .thenReturn("http://localhost:8080/api/files/generated-images/consistent.png");

        StoryboardService service = new StoryboardService(
                storyboardMapper,
                scriptMapper,
                taskRecordMapper,
                characterMapper,
                sceneMapper,
                aiProviderFactory,
                projectService,
                costEstimationService,
                publicAssetStorageService,
                new ObjectMapper(),
                selfProvider);

        Storyboard shot = new Storyboard();
        shot.setId(10L);
        shot.setProjectId(2L);
        shot.setShotNo(3);
        shot.setCameraAngle("wide");
        shot.setDescription("许氏董事会玻璃会议室，许知意把硬盘插入读卡器，大屏亮起证据。");
        shot.setImagePrompt("现代董事会，许知意把硬盘放在会议桌上。");

        service.ensureShotsHaveImages(1L, 2L, List.of(shot));

        assertThat(shot.getCharacterId()).isEqualTo(88L);
        assertThat(shot.getImageUrl()).contains("consistent.png");
        verify(storyboardMapper, times(2)).updateById(shot);
    }

    @Test
    void ensureShotsHaveImagesPersistsBackfilledCharacterEvenWhenImageGenerationFails() {
        StoryboardMapper storyboardMapper = mock(StoryboardMapper.class);
        ScriptMapper scriptMapper = mock(ScriptMapper.class);
        TaskRecordMapper taskRecordMapper = mock(TaskRecordMapper.class);
        CharacterMapper characterMapper = mock(CharacterMapper.class);
        SceneMapper sceneMapper = mock(SceneMapper.class);
        AiProviderFactory aiProviderFactory = mock(AiProviderFactory.class);
        ProjectService projectService = mock(ProjectService.class);
        CostEstimationService costEstimationService = mock(CostEstimationService.class);
        PublicAssetStorageService publicAssetStorageService = mock(PublicAssetStorageService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StoryboardService> selfProvider = mock(ObjectProvider.class);
        ImageAiProvider imageProvider = mock(ImageAiProvider.class);

        Character heroine = new Character();
        heroine.setId(88L);
        heroine.setProjectId(2L);
        heroine.setName("许知意");

        when(aiProviderFactory.getImageProvider(1L)).thenReturn(imageProvider);
        when(projectService.getProject(1L, 2L)).thenReturn(project());
        when(characterMapper.selectList(any())).thenReturn(List.of(heroine));
        when(costEstimationService.getOptimalImageSize("wide")).thenReturn("1024x1792");
        when(imageProvider.generateImage(anyString(), eq("1024x1792"), eq("vivid"), anyList(), any()))
                .thenThrow(new RuntimeException("provider timeout"));

        StoryboardService service = new StoryboardService(
                storyboardMapper,
                scriptMapper,
                taskRecordMapper,
                characterMapper,
                sceneMapper,
                aiProviderFactory,
                projectService,
                costEstimationService,
                publicAssetStorageService,
                new ObjectMapper(),
                selfProvider);

        Storyboard shot = new Storyboard();
        shot.setId(10L);
        shot.setProjectId(2L);
        shot.setShotNo(3);
        shot.setCameraAngle("wide");
        shot.setDescription("许知意把硬盘插入读卡器，大屏亮起证据。");

        assertThatThrownBy(() -> service.ensureShotsHaveImages(1L, 2L, List.of(shot)))
                .hasMessageContaining("图片生成失败");

        assertThat(shot.getCharacterId()).isEqualTo(88L);
        verify(storyboardMapper).updateById(shot);
    }

    private Project project() {
        Project project = new Project();
        project.setId(2L);
        project.setProjectType("真人短剧");
        project.setGenre("古装复仇");
        return project;
    }
}
