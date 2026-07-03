package com.niren.drama.service;

import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.ImageAiProvider;
import com.niren.drama.entity.Character;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CharacterServiceTest {

    private final CharacterMapper characterMapper = mock(CharacterMapper.class);
    private final TaskRecordMapper taskRecordMapper = mock(TaskRecordMapper.class);
    private final AiProviderFactory aiProviderFactory = mock(AiProviderFactory.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final PublicAssetStorageService publicAssetStorageService = mock(PublicAssetStorageService.class);
    private final ConsistencyBibleService consistencyBibleService = mock(ConsistencyBibleService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<CharacterService> selfProvider = mock(ObjectProvider.class);
    private final CharacterService service = new CharacterService(
            characterMapper,
            taskRecordMapper,
            aiProviderFactory,
            projectService,
            publicAssetStorageService,
            consistencyBibleService,
            selfProvider);

    @Test
    void generateCharacterImageAsyncStoresThreePortraitUrls() {
        Character character = new Character();
        character.setId(10L);
        character.setProjectId(20L);
        character.setName("林晚");
        character.setGender("female");
        character.setAge("26");
        character.setAppearance("长发，冷白皮");

        TaskRecord task = new TaskRecord();
        ImageAiProvider imageProvider = mock(ImageAiProvider.class);
        when(taskRecordMapper.selectById(30L)).thenReturn(task);
        when(aiProviderFactory.getImageProvider(1L)).thenReturn(imageProvider);
        when(projectService.getProject(1L, 20L)).thenReturn(new Project());
        when(imageProvider.generateImage(any(String.class), eq("1024x1024"), eq("vivid")))
                .thenReturn("https://img.example/1.png")
                .thenReturn("https://img.example/2.png")
                .thenReturn("https://img.example/3.png");

        service.generateCharacterImageAsync(1L, character, 30L);

        assertThat(character.getImageUrl()).isEqualTo("https://img.example/1.png");
        assertThat(character.getImageUrls()).isEqualTo("[\"https://img.example/1.png\",\"https://img.example/2.png\",\"https://img.example/3.png\"]");
        assertThat(task.getStatus()).isEqualTo("SUCCESS");
        assertThat(task.getResult()).isEqualTo(character.getImageUrls());
        verify(characterMapper).updateById(character);
    }
}
