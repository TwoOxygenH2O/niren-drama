package com.niren.drama.service;

import com.niren.drama.ai.TtsAuditionAudioGenerator;
import com.niren.drama.ai.TtsAuditionGenerationResult;
import com.niren.drama.ai.impl.ComfyUiTtsProviderFactory;
import com.niren.drama.ai.impl.MockTtsProvider;
import com.niren.drama.dto.tts.TtsAuditionRequest;
import com.niren.drama.entity.Character;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.StoryboardMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import com.niren.drama.service.storage.StoredAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TtsAuditionServiceTest {

    private final CharacterMapper characterMapper = mock(CharacterMapper.class);
    private final StoryboardMapper storyboardMapper = mock(StoryboardMapper.class);
    private final TaskRecordMapper taskRecordMapper = mock(TaskRecordMapper.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final PublicAssetStorageService storageService = mock(PublicAssetStorageService.class);
    private final ComfyUiTtsProviderFactory providerFactory = mock(ComfyUiTtsProviderFactory.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<TtsAuditionService> selfProvider = mock(ObjectProvider.class);
    private final AtomicLong ids = new AtomicLong(100);
    private TtsAuditionService service;

    @BeforeEach
    void setUp() {
        service = new TtsAuditionService(characterMapper, storyboardMapper, taskRecordMapper, projectService,
                storageService, providerFactory, selfProvider);
        TtsAuditionService asyncProxy = mock(TtsAuditionService.class);
        when(selfProvider.getObject()).thenReturn(asyncProxy);
        when(projectService.getProject(7L, 9L)).thenReturn(project());
        when(taskRecordMapper.insert(any(TaskRecord.class))).thenAnswer(invocation -> {
            TaskRecord task = invocation.getArgument(0);
            task.setId(ids.incrementAndGet());
            return 1;
        });
        when(taskRecordMapper.selectById(anyLong())).thenAnswer(invocation -> {
            TaskRecord task = new TaskRecord();
            task.setId(invocation.getArgument(0));
            task.setProjectId(9L);
            task.setUserId(7L);
            task.setTaskType("TTS_AUDITION");
            return task;
        });
    }

    @Test
    void auditionPartialFailureSucceedsAndDoesNotMutateStoryboardAudio() throws Exception {
        Character daughter = character(1L, "女儿", "female");
        when(characterMapper.selectList(any())).thenReturn(List.of(daughter));
        byte[] wav = new MockTtsProvider().synthesize("试听", "narrator", 1.0f, 1.0f);
        TtsAuditionAudioGenerator generator = mock(TtsAuditionAudioGenerator.class);
        when(providerFactory.create(7L)).thenReturn(generator);
        when(generator.generate(any()))
                .thenReturn(new TtsAuditionGenerationResult(wav, "prompt-1", "http://comfy/view?filename=1.wav", "inline", 1.2d))
                .thenThrow(new RuntimeException("ComfyUI 节点失败"));
        when(storageService.storeBytes(eq(wav), eq("audios/audition/9/101"), any(), eq("audio/wav"), eq("wav")))
                .thenReturn(new StoredAsset("http://files/audition-1.wav", "local", "audition-1.wav", wav.length, "audio/wav", "audition-1.wav"));

        TtsAuditionRequest request = new TtsAuditionRequest();
        request.setCharacterIds(List.of(1L));
        request.setIncludeNarrator(false);
        request.setCandidateCount(2);
        TaskRecord task = service.startAudition(7L, 9L, request);
        service.generateAuditionAsync(7L, 9L, task.getId(), request);

        assertThat(task.getTaskType()).isEqualTo("TTS_AUDITION");
        ArgumentCaptor<TaskRecord> captor = ArgumentCaptor.forClass(TaskRecord.class);
        verify(taskRecordMapper, atLeastOnce()).updateById(captor.capture());
        TaskRecord finalUpdate = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(finalUpdate.getStatus()).isEqualTo("SUCCESS");
        assertThat(finalUpdate.getMessage()).contains("成功1条", "失败1条");
        assertThat(finalUpdate.getResult()).contains("http://files/audition-1.wav", "ComfyUI 节点失败");
        verify(storyboardMapper, never()).updateById(any());
    }

    @Test
    void clampsRequestedCandidateCountToThree() throws Exception {
        ReflectionTestUtils.setField(service, "defaultCandidateCount", 3);
        ReflectionTestUtils.setField(service, "maxRoles", 4);
        ReflectionTestUtils.setField(service, "maxTextChars", 80);
        Character daughter = character(1L, "女儿", "female");
        when(characterMapper.selectList(any())).thenReturn(List.of(daughter));
        byte[] wav = new MockTtsProvider().synthesize("试听", "narrator", 1.0f, 1.0f);
        TtsAuditionAudioGenerator generator = mock(TtsAuditionAudioGenerator.class);
        when(providerFactory.create(7L)).thenReturn(generator);
        when(generator.generate(any()))
                .thenReturn(new TtsAuditionGenerationResult(wav, "prompt-1", "http://comfy/view?filename=1.wav", "inline", 1.2d));
        when(storageService.storeBytes(eq(wav), eq("audios/audition/9/101"), any(), eq("audio/wav"), eq("wav")))
                .thenReturn(new StoredAsset("http://files/audition.wav", "local", "audition.wav", wav.length, "audio/wav", "audition.wav"));

        TtsAuditionRequest request = new TtsAuditionRequest();
        request.setCharacterIds(List.of(1L));
        request.setIncludeNarrator(false);
        request.setCandidateCount(20);
        TaskRecord task = service.startAudition(7L, 9L, request);
        service.generateAuditionAsync(7L, 9L, task.getId(), request);

        verify(generator, times(3)).generate(any());
    }

    private Project project() {
        Project project = new Project();
        project.setId(9L);
        project.setName("测试短剧");
        project.setProjectType("真人短剧");
        project.setGenre("都市情感");
        return project;
    }

    private Character character(Long id, String name, String gender) {
        Character character = new Character();
        character.setId(id);
        character.setProjectId(9L);
        character.setName(name);
        character.setGender(gender);
        character.setDescription("角色设定");
        character.setPersonality("克制");
        return character;
    }
}
