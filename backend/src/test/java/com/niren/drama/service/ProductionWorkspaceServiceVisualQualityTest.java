package com.niren.drama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.ProductionIssue;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.mapper.AssetSnapshotMapper;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.ConsistencyBibleMapper;
import com.niren.drama.mapper.ProductionIssueMapper;
import com.niren.drama.mapper.SceneMapper;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.StoryboardMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductionWorkspaceServiceVisualQualityTest {

    @TempDir
    Path tempDir;

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
    void runQualityCheckBlocksVideoModeShotWhenDynamicVideoIsMissing() throws Exception {
        Project project = new Project();
        project.setId(13L);
        project.setUserId(7L);
        project.setName("Missing video");
        when(projectService.getProject(7L, 13L)).thenReturn(project);

        Storyboard shot = new Storyboard();
        shot.setId(103L);
        shot.setProjectId(13L);
        shot.setShotNo(1);
        shot.setDuration(8);
        shot.setRenderMode("video");
        shot.setImageUrl("http://localhost:8080/api/files/generated-images/frame.png");
        when(storyboardService.listByProject(13L)).thenReturn(List.of(shot));
        when(scriptMapper.selectList(any())).thenReturn(List.of());
        when(taskRecordMapper.selectList(any())).thenReturn(List.of());
        when(assetSnapshotMapper.selectList(any())).thenReturn(List.of());
        when(productionIssueMapper.selectList(any())).thenReturn(List.of());
        when(videoCompositionService.getLatestVideoTask(13L)).thenReturn(null);

        ProductionWorkspaceService service = new ProductionWorkspaceService(
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
        ReflectionTestUtils.setField(service, "uploadPath", tempDir.toString());
        ReflectionTestUtils.setField(service, "uploadBaseUrl", "http://localhost:8080/api/files");

        Map<String, Object> result = service.runQualityCheck(7L, 13L, Map.of("shotIds", List.of(103L)));

        assertThat(result.get("checkedShots")).isEqualTo(1);
        ArgumentCaptor<ProductionIssue> issueCaptor = ArgumentCaptor.forClass(ProductionIssue.class);
        verify(productionIssueMapper).insert(issueCaptor.capture());
        assertThat(issueCaptor.getValue().getIssueType()).isEqualTo("missing_video");
        assertThat(issueCaptor.getValue().getSeverity()).isEqualTo("blocking");
        assertThat(issueCaptor.getValue().getRecommendedAction()).isEqualTo("retryVideo");
    }

    @Test
    void durationCheckAllowsFourSecondWanSourceForOpticalFlowExtension() throws Exception {
        ProductionWorkspaceService service = new ProductionWorkspaceService(
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
        Method method = ProductionWorkspaceService.class.getDeclaredMethod(
                "isVideoDurationOutOfRange",
                double.class,
                int.class);
        method.setAccessible(true);

        assertThat((boolean) method.invoke(service, 4.06d, 8)).isFalse();
        assertThat((boolean) method.invoke(service, 2.9d, 8)).isTrue();
        assertThat((boolean) method.invoke(service, 17.0d, 8)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void qualityIssueTypesIncludeGeneratedAnalyzerFindingsForClosure() throws Exception {
        Field field = ProductionWorkspaceService.class.getDeclaredField("QUALITY_ISSUE_TYPES");
        field.setAccessible(true);

        List<String> types = (List<String>) field.get(null);

        assertThat(types).contains("washed_gray_video", "low_effective_fps");
    }

    @Test
    void runQualityCheckPersistsVisualQualityFindings() throws Exception {
        Path video = tempDir.resolve("generated-videos").resolve("bad.mp4");
        Files.createDirectories(video.getParent());
        Files.write(video, new byte[]{0, 1, 2});

        Project project = new Project();
        project.setId(11L);
        project.setUserId(7L);
        project.setName("Visual CASR");
        when(projectService.getProject(7L, 11L)).thenReturn(project);

        Storyboard shot = new Storyboard();
        shot.setId(101L);
        shot.setProjectId(11L);
        shot.setShotNo(1);
        shot.setDuration(5);
        shot.setImageUrl("http://localhost:8080/api/files/generated-images/frame.png");
        shot.setVideoUrl("http://localhost:8080/api/files/generated-videos/bad.mp4");
        when(storyboardService.listByProject(11L)).thenReturn(List.of(shot));
        when(scriptMapper.selectList(any())).thenReturn(List.of());
        when(taskRecordMapper.selectList(any())).thenReturn(List.of());
        when(assetSnapshotMapper.selectList(any())).thenReturn(List.of());
        when(productionIssueMapper.selectList(any())).thenReturn(List.of());
        when(videoCompositionService.getLatestVideoTask(11L)).thenReturn(null);

        when(visualQualityAnalyzer.analyze(eq(video), eq(5))).thenReturn(new VisualQualityReport(
                true,
                Map.of("sampledFrames", 8, "averageSharpness", 0.01d),
                List.of(new VisualQualityFinding(
                        "unwatchable_visual",
                        "blocking",
                        "视觉质量不可用",
                        "抽帧检测显示镜头主体不可读，不能进入发布。",
                        "retryVideo",
                        Map.of("averageSharpness", 0.01d)
                ))
        ));

        ProductionWorkspaceService service = new ProductionWorkspaceService(
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
        ReflectionTestUtils.setField(service, "uploadPath", tempDir.toString());
        ReflectionTestUtils.setField(service, "uploadBaseUrl", "http://localhost:8080/api/files");
        ReflectionTestUtils.setField(service, "ffmpegPath", "");
        ReflectionTestUtils.setField(service, "ffprobePath", "");

        Map<String, Object> result = service.runQualityCheck(7L, 11L, Map.of("shotIds", List.of(101L)));

        assertThat(result.get("checkedShots")).isEqualTo(1);
        ArgumentCaptor<ProductionIssue> issueCaptor = ArgumentCaptor.forClass(ProductionIssue.class);
        verify(productionIssueMapper).insert(issueCaptor.capture());
        assertThat(issueCaptor.getValue().getIssueType()).isEqualTo("unwatchable_visual");
        assertThat(issueCaptor.getValue().getSeverity()).isEqualTo("blocking");
        assertThat(issueCaptor.getValue().getMetadata()).contains("averageSharpness");
    }

    @Test
    void runQualityCheckPersistsVlmReviewFindings() throws Exception {
        Path video = tempDir.resolve("generated-videos").resolve("shot.mp4");
        Path reference = tempDir.resolve("generated-images").resolve("frame.png");
        Files.createDirectories(video.getParent());
        Files.createDirectories(reference.getParent());
        Files.write(video, new byte[]{0, 1, 2});
        Files.write(reference, new byte[]{3, 4, 5});

        Project project = new Project();
        project.setId(12L);
        project.setUserId(7L);
        project.setName("VLM CASR");
        when(projectService.getProject(7L, 12L)).thenReturn(project);

        Storyboard shot = new Storyboard();
        shot.setId(102L);
        shot.setProjectId(12L);
        shot.setShotNo(1);
        shot.setDuration(6);
        shot.setDescription("女主在王府门前转身拔簪，眼神决绝。");
        shot.setImageUrl("http://localhost:8080/api/files/generated-images/frame.png");
        shot.setVideoUrl("http://localhost:8080/api/files/generated-videos/shot.mp4");
        when(storyboardService.listByProject(12L)).thenReturn(List.of(shot));
        when(scriptMapper.selectList(any())).thenReturn(List.of());
        when(taskRecordMapper.selectList(any())).thenReturn(List.of());
        when(assetSnapshotMapper.selectList(any())).thenReturn(List.of());
        when(productionIssueMapper.selectList(any())).thenReturn(List.of());
        when(videoCompositionService.getLatestVideoTask(12L)).thenReturn(null);

        when(visualQualityAnalyzer.analyze(eq(video), eq(6))).thenReturn(new VisualQualityReport(
                true,
                Map.of("sampledFrames", 8, "averageFrameDiff", 0.02d),
                List.of()
        ));
        when(visualReviewService.review(eq(7L), eq(shot), eq(video), eq(reference), eq(6), any()))
                .thenReturn(new VisualReviewReport(
                        true,
                        null,
                        Map.of("reviewer", "vlm", "keyframes", 3),
                        List.of(new VisualReviewFinding(
                                "identity_drift",
                                "blocking",
                                "人物身份漂移",
                                "关键帧中的女主脸型与参考图明显不同。",
                                "retryVideo",
                                Map.of("identityConsistency", 42)
                        ))
                ));

        ProductionWorkspaceService service = new ProductionWorkspaceService(
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
        ReflectionTestUtils.setField(service, "uploadPath", tempDir.toString());
        ReflectionTestUtils.setField(service, "uploadBaseUrl", "http://localhost:8080/api/files");
        ReflectionTestUtils.setField(service, "ffmpegPath", "");
        ReflectionTestUtils.setField(service, "ffprobePath", "");

        Map<String, Object> result = service.runQualityCheck(7L, 12L, Map.of("shotIds", List.of(102L)));

        assertThat(result.get("checkedShots")).isEqualTo(1);
        ArgumentCaptor<ProductionIssue> issueCaptor = ArgumentCaptor.forClass(ProductionIssue.class);
        verify(productionIssueMapper).insert(issueCaptor.capture());
        assertThat(issueCaptor.getValue().getIssueType()).isEqualTo("identity_drift");
        assertThat(issueCaptor.getValue().getSeverity()).isEqualTo("blocking");
        assertThat(issueCaptor.getValue().getMetadata()).contains("vlm", "identityConsistency");
    }

    @Test
    void exportPackageCreatesZipWithManifestAndLocalAssets() throws Exception {
        Path video = tempDir.resolve("videos").resolve("final.mp4");
        Path frame = tempDir.resolve("generated-images").resolve("frame.png");
        Path audio = tempDir.resolve("audios").resolve("voice.wav");
        Files.createDirectories(video.getParent());
        Files.createDirectories(frame.getParent());
        Files.createDirectories(audio.getParent());
        Files.write(video, new byte[]{0, 1, 2});
        Files.write(frame, new byte[]{3, 4, 5});
        Files.write(audio, new byte[]{6, 7, 8});

        Project project = new Project();
        project.setId(15L);
        project.setUserId(7L);
        project.setName("Exportable");
        when(projectService.getProject(7L, 15L)).thenReturn(project);

        Storyboard shot = new Storyboard();
        shot.setId(151L);
        shot.setProjectId(15L);
        shot.setShotNo(1);
        shot.setImageUrl("http://localhost:8080/api/files/generated-images/frame.png");
        shot.setAudioUrl("http://localhost:8080/api/files/audios/voice.wav");
        when(storyboardService.listByProject(15L)).thenReturn(List.of(shot));
        when(productionIssueMapper.selectList(any())).thenReturn(List.of());

        TaskRecord composeTask = new TaskRecord();
        composeTask.setStatus("SUCCESS");
        composeTask.setResult("{\"videoUrl\":\"http://localhost:8080/api/files/videos/final.mp4\"}");
        when(videoCompositionService.getLatestVideoTask(15L)).thenReturn(composeTask);
        when(videoCompositionService.extractVideoUrl(composeTask.getResult()))
                .thenReturn("http://localhost:8080/api/files/videos/final.mp4");

        ProductionWorkspaceService service = new ProductionWorkspaceService(
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
        ReflectionTestUtils.setField(service, "uploadPath", tempDir.toString());
        ReflectionTestUtils.setField(service, "uploadBaseUrl", "http://localhost:8080/api/files");

        Map<String, Object> manifest = service.exportPackage(7L, 15L, Map.of("platformProfile", "douyin"));

        assertThat(manifest.get("ready")).isEqualTo(true);
        assertThat(manifest.get("packageUrl")).asString().contains("/exports/15/").endsWith(".zip");
        Path packagePath = Path.of(String.valueOf(manifest.get("packagePath")));
        assertThat(Files.exists(packagePath)).isTrue();
        try (ZipFile zip = new ZipFile(packagePath.toFile())) {
            assertThat(zip.getEntry("manifest.json")).isNotNull();
            assertThat(zip.getEntry("video/final.mp4")).isNotNull();
            assertThat(zip.getEntry("images/shot-1-frame.png")).isNotNull();
            assertThat(zip.getEntry("audio/shot-1-voice.wav")).isNotNull();
        }
    }

    @Test
    void runEpisodePipelineStartsNextMissingFirstFrameTask() {
        Project project = new Project();
        project.setId(16L);
        project.setUserId(7L);
        project.setName("Pipeline");
        when(projectService.getProject(7L, 16L)).thenReturn(project);

        Script script = new Script();
        script.setProjectId(16L);
        script.setEpisodeNo(1);
        script.setContent("第1集剧本");
        when(scriptMapper.selectList(any())).thenReturn(List.of(script));

        Storyboard missing = new Storyboard();
        missing.setId(161L);
        missing.setProjectId(16L);
        missing.setShotNo(1);
        Storyboard ready = new Storyboard();
        ready.setId(162L);
        ready.setProjectId(16L);
        ready.setShotNo(2);
        ready.setImageUrl("http://localhost:8080/api/files/generated-images/ready.png");
        when(storyboardService.listByProject(16L)).thenReturn(List.of(missing, ready));

        TaskRecord imageTask = new TaskRecord();
        imageTask.setId(700L);
        imageTask.setTaskType("IMAGE_GEN");
        imageTask.setStatus("PENDING");
        imageTask.setProgress(0);
        when(storyboardService.startGenerateStoryboardImages(7L, 16L, List.of(161L))).thenReturn(imageTask);
        when(taskRecordMapper.selectList(any())).thenReturn(List.of());
        when(assetSnapshotMapper.selectList(any())).thenReturn(List.of());
        when(productionIssueMapper.selectList(any())).thenReturn(List.of());
        when(consistencyBibleMapper.selectList(any())).thenReturn(List.of());
        when(videoCompositionService.getLatestVideoTask(16L)).thenReturn(null);

        ProductionWorkspaceService service = new ProductionWorkspaceService(
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
        ReflectionTestUtils.setField(service, "uploadPath", tempDir.toString());
        ReflectionTestUtils.setField(service, "uploadBaseUrl", "http://localhost:8080/api/files");

        Map<String, Object> result = service.repair(7L, 16L, Map.of("action", "runEpisodePipeline"));

        assertThat(result.get("pipeline")).asString().contains("firstFrames");
        assertThat(result).containsKey("task");
        verify(storyboardService).startGenerateStoryboardImages(7L, 16L, List.of(161L));
    }
}
