package com.niren.drama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.ImageAiProvider;
import com.niren.drama.ai.TtsProvider;
import com.niren.drama.entity.Character;
import com.niren.drama.entity.ProductionIssue;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.ProductionIssueMapper;
import com.niren.drama.mapper.SceneMapper;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.StoryboardMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import com.niren.drama.service.storage.StoredAsset;
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
        ProductionIssueMapper productionIssueMapper = mock(ProductionIssueMapper.class);
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
                productionIssueMapper,
                aiProviderFactory,
                projectService,
                costEstimationService,
                publicAssetStorageService,
                mock(ConsistencyBibleService.class),
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
        ProductionIssueMapper productionIssueMapper = mock(ProductionIssueMapper.class);
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
                productionIssueMapper,
                aiProviderFactory,
                projectService,
                costEstimationService,
                publicAssetStorageService,
                mock(ConsistencyBibleService.class),
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
        ProductionIssueMapper productionIssueMapper = mock(ProductionIssueMapper.class);
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
                productionIssueMapper,
                aiProviderFactory,
                projectService,
                costEstimationService,
                publicAssetStorageService,
                mock(ConsistencyBibleService.class),
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

    @Test
    void generateStoryboardAudioWritesBackMeasuredWavDuration() throws Exception {
        StoryboardMapper storyboardMapper = mock(StoryboardMapper.class);
        ScriptMapper scriptMapper = mock(ScriptMapper.class);
        TaskRecordMapper taskRecordMapper = mock(TaskRecordMapper.class);
        CharacterMapper characterMapper = mock(CharacterMapper.class);
        SceneMapper sceneMapper = mock(SceneMapper.class);
        ProductionIssueMapper productionIssueMapper = mock(ProductionIssueMapper.class);
        AiProviderFactory aiProviderFactory = mock(AiProviderFactory.class);
        ProjectService projectService = mock(ProjectService.class);
        CostEstimationService costEstimationService = mock(CostEstimationService.class);
        PublicAssetStorageService publicAssetStorageService = mock(PublicAssetStorageService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StoryboardService> selfProvider = mock(ObjectProvider.class);
        TtsProvider ttsProvider = mock(TtsProvider.class);

        TaskRecord task = new TaskRecord();
        task.setId(55L);
        task.setProjectId(2L);
        task.setStatus("PENDING");
        when(taskRecordMapper.selectById(55L)).thenReturn(task);
        when(aiProviderFactory.getTtsProvider(1L)).thenReturn(ttsProvider);
        when(projectService.getProject(2L)).thenReturn(project());
        when(ttsProvider.synthesize(anyString(), anyString(), eq(1.0f), eq(1.0f), any(), eq("Chinese")))
                .thenReturn(pcmWav(24_000, 1, 16, 4.2d));
        when(publicAssetStorageService.storeBytes(any(), eq("audios"), anyString(), eq("audio/wav"), eq("wav")))
                .thenReturn(new StoredAsset("http://localhost:8080/api/files/audios/voice.wav", "local", "audios/voice.wav", 10, "audio/wav", "voice.wav"));

        StoryboardService service = new StoryboardService(
                storyboardMapper,
                scriptMapper,
                taskRecordMapper,
                characterMapper,
                sceneMapper,
                productionIssueMapper,
                aiProviderFactory,
                projectService,
                costEstimationService,
                publicAssetStorageService,
                mock(ConsistencyBibleService.class),
                new ObjectMapper(),
                selfProvider);

        Storyboard shot = new Storyboard();
        shot.setId(10L);
        shot.setProjectId(2L);
        shot.setShotNo(1);
        shot.setDuration(3);
        shot.setDialogue("我一定会回来。");

        service.generateStoryboardAudioAsync(1L, 2L, List.of(shot), 55L);

        assertThat(shot.getAudioUrl()).contains("voice.wav");
        assertThat(shot.getDuration()).isEqualTo(5);
        verify(storyboardMapper).updateById(shot);
    }

    @Test
    void generateStoryboardAudioReportsEmptyTtsTextAsProductionIssue() {
        StoryboardMapper storyboardMapper = mock(StoryboardMapper.class);
        ScriptMapper scriptMapper = mock(ScriptMapper.class);
        TaskRecordMapper taskRecordMapper = mock(TaskRecordMapper.class);
        CharacterMapper characterMapper = mock(CharacterMapper.class);
        SceneMapper sceneMapper = mock(SceneMapper.class);
        ProductionIssueMapper productionIssueMapper = mock(ProductionIssueMapper.class);
        AiProviderFactory aiProviderFactory = mock(AiProviderFactory.class);
        ProjectService projectService = mock(ProjectService.class);
        CostEstimationService costEstimationService = mock(CostEstimationService.class);
        PublicAssetStorageService publicAssetStorageService = mock(PublicAssetStorageService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StoryboardService> selfProvider = mock(ObjectProvider.class);
        TtsProvider ttsProvider = mock(TtsProvider.class);

        TaskRecord task = new TaskRecord();
        task.setId(56L);
        task.setProjectId(2L);
        task.setStatus("PENDING");
        when(taskRecordMapper.selectById(56L)).thenReturn(task);
        when(aiProviderFactory.getTtsProvider(1L)).thenReturn(ttsProvider);
        when(projectService.getProject(2L)).thenReturn(project());
        when(productionIssueMapper.selectCount(any())).thenReturn(0L);

        StoryboardService service = new StoryboardService(
                storyboardMapper,
                scriptMapper,
                taskRecordMapper,
                characterMapper,
                sceneMapper,
                productionIssueMapper,
                aiProviderFactory,
                projectService,
                costEstimationService,
                publicAssetStorageService,
                mock(ConsistencyBibleService.class),
                new ObjectMapper(),
                selfProvider);

        Storyboard shot = new Storyboard();
        shot.setId(11L);
        shot.setProjectId(2L);
        shot.setShotNo(2);

        service.generateStoryboardAudioAsync(1L, 2L, List.of(shot), 56L);

        verify(productionIssueMapper).insert(argThat(issue ->
                issue instanceof ProductionIssue
                        && "missing_tts_text".equals(((ProductionIssue) issue).getIssueType())
                        && "generateAudio".equals(((ProductionIssue) issue).getRecommendedAction())));
    }

    private static byte[] pcmWav(int sampleRate, int channels, int bitsPerSample, double seconds) {
        int bytesPerSample = bitsPerSample / 8;
        int samples = (int) Math.round(sampleRate * seconds);
        int dataSize = samples * channels * bytesPerSample;
        byte[] wav = new byte[44 + dataSize];
        writeAscii(wav, 0, "RIFF");
        writeIntLE(wav, 4, 36 + dataSize);
        writeAscii(wav, 8, "WAVE");
        writeAscii(wav, 12, "fmt ");
        writeIntLE(wav, 16, 16);
        writeShortLE(wav, 20, (short) 1);
        writeShortLE(wav, 22, (short) channels);
        writeIntLE(wav, 24, sampleRate);
        writeIntLE(wav, 28, sampleRate * channels * bytesPerSample);
        writeShortLE(wav, 32, (short) (channels * bytesPerSample));
        writeShortLE(wav, 34, (short) bitsPerSample);
        writeAscii(wav, 36, "data");
        writeIntLE(wav, 40, dataSize);
        return wav;
    }

    private static void writeAscii(byte[] target, int offset, String value) {
        for (int i = 0; i < value.length(); i++) {
            target[offset + i] = (byte) value.charAt(i);
        }
    }

    private static void writeIntLE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void writeShortLE(byte[] target, int offset, short value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private Project project() {
        Project project = new Project();
        project.setId(2L);
        project.setProjectType("真人短剧");
        project.setGenre("古装复仇");
        return project;
    }
}
