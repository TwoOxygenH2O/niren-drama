package com.niren.drama.service;

import com.niren.drama.entity.CasrRun;
import com.niren.drama.entity.ConsistencyBible;
import com.niren.drama.entity.ProductionIssue;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.mapper.CasrRunMapper;
import com.niren.drama.mapper.ConsistencyBibleMapper;
import com.niren.drama.mapper.ProductionIssueMapper;
import com.niren.drama.mapper.ProjectMapper;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.StoryboardMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CasrDemoServiceTest {

    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final ScriptMapper scriptMapper = mock(ScriptMapper.class);
    private final StoryboardMapper storyboardMapper = mock(StoryboardMapper.class);
    private final ProductionIssueMapper productionIssueMapper = mock(ProductionIssueMapper.class);
    private final ConsistencyBibleMapper consistencyBibleMapper = mock(ConsistencyBibleMapper.class);
    private final CasrRunMapper casrRunMapper = mock(CasrRunMapper.class);
    private final CasrDemoService service = new CasrDemoService(
            projectMapper,
            scriptMapper,
            storyboardMapper,
            productionIssueMapper,
            consistencyBibleMapper,
            casrRunMapper);

    @Test
    void createDemoInsertsProjectShotsIssuesBibleAndSeedRun() {
        Map<String, Object> result = service.createDemo(7L);

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).insert(projectCaptor.capture());
        verify(scriptMapper).insert(any(Script.class));
        verify(storyboardMapper, times(7)).insert(any(Storyboard.class));
        verify(productionIssueMapper, times(5)).insert(any(ProductionIssue.class));
        verify(consistencyBibleMapper, times(2)).insert(any(ConsistencyBible.class));
        verify(casrRunMapper).insert(any(CasrRun.class));

        assertThat(projectCaptor.getValue().getUserId()).isEqualTo(7L);
        assertThat(projectCaptor.getValue().getName()).contains("CASR");
        assertThat(result).containsKeys("projectId", "projectName", "route");
    }
}
