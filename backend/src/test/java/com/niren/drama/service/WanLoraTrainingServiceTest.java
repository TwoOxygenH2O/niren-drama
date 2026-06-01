package com.niren.drama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.AiConfig;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.AiConfigMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanLoraTrainingServiceTest {

    private final AiConfigMapper aiConfigMapper = mock(AiConfigMapper.class);
    private final TaskRecordMapper taskRecordMapper = mock(TaskRecordMapper.class);
    private final WanLoraTrainingRunner trainingRunner = mock(WanLoraTrainingRunner.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private WanLoraTrainingService service;

    @BeforeEach
    void setUp() {
        service = new WanLoraTrainingService(aiConfigMapper, taskRecordMapper, trainingRunner, objectMapper);
        service.setTrainingRootForTest(tempDir);
        doAnswer(invocation -> {
            TaskRecord task = invocation.getArgument(0);
            task.setId(99L);
            return 1;
        }).when(taskRecordMapper).insert(any(TaskRecord.class));
    }

    @Test
    void submitRequiresLicenseConfirmationBeforeTrainingUserMaterials() {
        AiConfig config = wanComfyUiConfig();
        when(aiConfigMapper.selectOne(any())).thenReturn(config);

        assertThatThrownBy(() -> service.submit(
                7L,
                11L,
                List.of(videoFile()),
                "short drama continuity sample",
                false,
                "trial",
                8,
                1,
                true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("授权");
    }

    @Test
    void submitRejectsNonWanComfyUiVideoConfigs() {
        AiConfig config = wanComfyUiConfig();
        config.setProvider("aliyun");
        when(aiConfigMapper.selectOne(any())).thenReturn(config);

        assertThatThrownBy(() -> service.submit(
                7L,
                11L,
                List.of(videoFile()),
                "short drama continuity sample",
                true,
                "trial",
                8,
                1,
                true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ComfyUI");
    }

    @Test
    void submitStagesVideoFilesCreatesTaskAndStartsRunner() throws Exception {
        AiConfig config = wanComfyUiConfig();
        when(aiConfigMapper.selectOne(any())).thenReturn(config);

        TaskRecord task = service.submit(
                7L,
                11L,
                List.of(videoFile()),
                "Keep the same actor, outfit and scene in one continuous shot.",
                true,
                "pilot run",
                8,
                1,
                true);

        assertThat(task.getId()).isEqualTo(99L);
        assertThat(task.getTaskType()).isEqualTo("WAN22_LORA_TRAIN");
        assertThat(task.getStatus()).isEqualTo("PENDING");
        assertThat(task.getProgress()).isZero();
        assertThat(task.getRefId()).isEqualTo(11L);
        assertThat(task.getMessage()).contains("Wan2.2 LoRA");

        ArgumentCaptor<WanLoraTrainingService.TrainingContext> contextCaptor =
                ArgumentCaptor.forClass(WanLoraTrainingService.TrainingContext.class);
        verify(trainingRunner).startTraining(contextCaptor.capture());
        WanLoraTrainingService.TrainingContext context = contextCaptor.getValue();
        assertThat(context.taskId()).isEqualTo(99L);
        assertThat(context.userId()).isEqualTo(7L);
        assertThat(context.configId()).isEqualTo(11L);
        assertThat(context.workflowFile()).isEqualTo("video_wan2_2_14B_i2v.json");
        assertThat(context.samples()).hasSize(1);
        assertThat(context.lowVram()).isTrue();
        assertThat(context.loraRank()).isEqualTo(8);
        assertThat(context.epochs()).isEqualTo(1);
        assertThat(Files.exists(context.samples().get(0).videoPath())).isTrue();
        assertThat(context.samples().get(0).originalFilename()).isEqualTo("shot01.mp4");
    }

    private AiConfig wanComfyUiConfig() {
        AiConfig config = new AiConfig();
        config.setId(11L);
        config.setUserId(7L);
        config.setConfigType("video");
        config.setProvider("comfyui");
        config.setBaseUrl("http://127.0.0.1:8188");
        config.setModel("wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors");
        config.setExtra("{\"workflowFile\":\"video_wan2_2_14B_i2v.json\"}");
        return config;
    }

    private MockMultipartFile videoFile() {
        return new MockMultipartFile("files", "shot01.mp4", "video/mp4", new byte[]{1, 2, 3, 4});
    }
}
