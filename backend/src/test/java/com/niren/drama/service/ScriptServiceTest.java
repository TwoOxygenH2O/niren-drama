package com.niren.drama.service;

import com.niren.drama.ai.AiOutputTruncatedException;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.TextAiProvider;
import com.niren.drama.dto.script.ScriptGenerateRequest;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScriptServiceTest {

    @Test
    void generateScriptContinuesOnceWhenAiOutputIsTruncated() {
        ScriptMapper scriptMapper = mock(ScriptMapper.class);
        CharacterMapper characterMapper = mock(CharacterMapper.class);
        TaskRecordMapper taskRecordMapper = mock(TaskRecordMapper.class);
        AiProviderFactory aiProviderFactory = mock(AiProviderFactory.class);
        ProjectService projectService = mock(ProjectService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ScriptService> selfProvider = mock(ObjectProvider.class);
        TextAiProvider textProvider = mock(TextAiProvider.class);

        Project project = new Project();
        project.setId(12L);
        project.setName("续写项目");
        project.setEpisodes(1);
        project.setEpisodeDuration(90);
        project.setCommonInfo("官方项目名称：续写项目\n人物：林晚，27岁，冷静。");
        when(projectService.getProject(7L, 12L)).thenReturn(project);
        when(aiProviderFactory.getTextProvider(7L)).thenReturn(textProvider);

        Script outline = new Script();
        outline.setId(99L);
        outline.setProjectId(12L);
        outline.setEpisodeNo(1);
        outline.setTitle("第1集");
        outline.setSummary("林晚发现证据，并在结尾被追堵。");
        when(scriptMapper.selectList(any()))
                .thenReturn(List.of(outline))
                .thenReturn(List.of(outline));

        TaskRecord task = new TaskRecord();
        task.setId(501L);
        task.setProjectId(12L);
        when(taskRecordMapper.selectById(501L)).thenReturn(task);

        when(textProvider.chat(anyString(), anyString()))
                .thenReturn("{\"characters\":[{\"name\":\"林晚\",\"gender\":\"female\",\"age\":\"27岁\",\"appearance\":\"冷白皮\",\"personality\":\"冷静\",\"description\":\"主角\"}]}")
                .thenThrow(new AiOutputTruncatedException("length", "【第1集】\n第1场 林晚发现硬盘。"))
                .thenReturn("第2场 她把硬盘藏进口袋。\n结尾：追兵破门。");

        ScriptService service = new ScriptService(
                scriptMapper,
                characterMapper,
                taskRecordMapper,
                aiProviderFactory,
                projectService,
                new ObjectMapper(),
                selfProvider);

        ScriptGenerateRequest request = new ScriptGenerateRequest();
        request.setProjectId(12L);
        request.setEpisodeNo(1);
        request.setTotalEpisodes(1);

        service.generateScriptAsync(7L, request, 501L);

        ArgumentCaptor<Script> scriptCaptor = ArgumentCaptor.forClass(Script.class);
        verify(scriptMapper).updateById(scriptCaptor.capture());
        assertThat(scriptCaptor.getValue().getContent())
                .contains("第1场 林晚发现硬盘")
                .contains("第2场 她把硬盘藏进口袋")
                .contains("追兵破门");
        assertThat(task.getStatus()).isEqualTo("SUCCESS");
    }
}
