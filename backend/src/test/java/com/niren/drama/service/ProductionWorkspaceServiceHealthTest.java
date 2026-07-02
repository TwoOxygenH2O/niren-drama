package com.niren.drama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.mapper.AssetSnapshotMapper;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.ConsistencyBibleMapper;
import com.niren.drama.mapper.ProductionIssueMapper;
import com.niren.drama.mapper.SceneMapper;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.StoryboardMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionWorkspaceServiceHealthTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private StoryboardService storyboardService;
    @Mock
    private VideoCompositionService videoCompositionService;
    @Mock
    private AiConfigService aiConfigService;
    @Mock
    private StoryboardMapper storyboardMapper;
    @Mock
    private ScriptMapper scriptMapper;
    @Mock
    private TaskRecordMapper taskRecordMapper;
    @Mock
    private AssetSnapshotMapper assetSnapshotMapper;
    @Mock
    private ProductionIssueMapper productionIssueMapper;
    @Mock
    private ConsistencyBibleMapper consistencyBibleMapper;
    @Mock
    private CharacterMapper characterMapper;
    @Mock
    private SceneMapper sceneMapper;
    @Mock
    private VisualQualityAnalyzer visualQualityAnalyzer;
    @Mock
    private VisualReviewService visualReviewService;

    @Test
    void healthReportsCsrfPolicyForStatelessJwtApi() throws Exception {
        when(aiConfigService.getDefaultByType(7L, "video")).thenReturn(null);
        ProductionWorkspaceService service = newService();
        ReflectionTestUtils.setField(service, "ffmpegPath", "");

        Map<String, Object> health = invokeBuildHealth(service, 7L);

        assertThat(health).containsKey("csrf");
        assertThat(health.get("csrf")).isEqualTo(Map.of(
                "status", "ok",
                "label", "CSRF 已关闭",
                "mode", "stateless-jwt",
                "enabled", false,
                "reason", "REST API 使用 Bearer JWT，不依赖 Cookie Session"
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeBuildHealth(ProductionWorkspaceService service, Long userId) throws Exception {
        Method method = ProductionWorkspaceService.class.getDeclaredMethod("buildHealth", Long.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, userId);
    }

    private ProductionWorkspaceService newService() {
        return new ProductionWorkspaceService(
                projectService,
                storyboardService,
                videoCompositionService,
                aiConfigService,
                storyboardMapper,
                scriptMapper,
                taskRecordMapper,
                assetSnapshotMapper,
                productionIssueMapper,
                consistencyBibleMapper,
                characterMapper,
                sceneMapper,
                new ObjectMapper(),
                visualQualityAnalyzer,
                visualReviewService
        );
    }
}
