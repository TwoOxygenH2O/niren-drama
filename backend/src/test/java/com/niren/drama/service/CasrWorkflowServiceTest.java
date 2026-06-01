package com.niren.drama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.CasrRun;
import com.niren.drama.entity.ConsistencyBible;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.mapper.CasrRunMapper;
import com.niren.drama.mapper.ConsistencyBibleMapper;
import com.niren.drama.mapper.ProductionIssueMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CasrWorkflowServiceTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private StoryboardService storyboardService;
    @Mock
    private ProductionIssueMapper productionIssueMapper;
    @Mock
    private ConsistencyBibleMapper consistencyBibleMapper;
    @Mock
    private TaskRecordMapper taskRecordMapper;
    @Mock
    private CasrRunMapper casrRunMapper;
    @Mock
    private ProductionWorkspaceService productionWorkspaceService;
    @Spy
    private CasrAnalysisService casrAnalysisService = new CasrAnalysisService(new NirenCasrInputAdapter());
    @Spy
    private CasrPolicySearchService casrPolicySearchService = new CasrPolicySearchService();
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CasrWorkflowService service;

    @Test
    void planPersistsStrategyWithoutExecutingRepairs() {
        prepareProjectContext();

        Map<String, Object> result = service.plan(7L, 11L);

        assertThat(result).containsKeys("analysis", "plan", "runId");
        ArgumentCaptor<CasrRun> runCaptor = ArgumentCaptor.forClass(CasrRun.class);
        verify(casrRunMapper).insert(runCaptor.capture());
        assertThat(runCaptor.getValue().getRunType()).isEqualTo("plan");
        assertThat(runCaptor.getValue().getPlanJson()).contains("preserve-continuity-wan");
        verify(productionWorkspaceService, never()).repair(any(), any(), any());
    }

    @Test
    void executeRunsOnlyActionsConfirmedByUser() {
        prepareProjectContext();
        when(productionWorkspaceService.repair(eq(7L), eq(11L), any())).thenReturn(Map.of("ok", true));

        Map<String, Object> result = service.execute(7L, 11L, Map.of(
                "optionId", "preserve-continuity-wan",
                "actionIds", List.of("snapshot")
        ));

        assertThat(result).containsKeys("analysis", "plan", "executedActions", "runId");
        assertThat(result.get("executedActions")).asList().hasSize(1);
        verify(productionWorkspaceService).repair(eq(7L), eq(11L), eq(Map.of(
                "action", "snapshot",
                "shotIds", List.of(101L)
        )));
    }

    private void prepareProjectContext() {
        Project project = new Project();
        project.setId(11L);
        project.setUserId(7L);
        project.setName("CASR Test");
        when(projectService.getProject(7L, 11L)).thenReturn(project);

        Storyboard shot = new Storyboard();
        shot.setId(101L);
        shot.setProjectId(11L);
        shot.setShotNo(1);
        shot.setDescription("rain alley");
        shot.setImageUrl("/frame.png");
        shot.setVideoTaskStatus("failed");
        shot.setVideoPrompt("fast motion");
        shot.setDuration(12);
        when(storyboardService.listByProject(11L)).thenReturn(List.of(shot));

        ConsistencyBible bible = new ConsistencyBible();
        bible.setProjectId(11L);
        bible.setBibleType("character");
        bible.setLockedAttributes("same face, white coat");
        when(consistencyBibleMapper.selectList(any())).thenReturn(List.of(bible));
        when(productionIssueMapper.selectList(any())).thenReturn(List.of());
        when(taskRecordMapper.selectList(any())).thenReturn(List.of());
    }
}
