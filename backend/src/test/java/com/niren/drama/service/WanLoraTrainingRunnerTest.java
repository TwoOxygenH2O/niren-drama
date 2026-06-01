package com.niren.drama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WanLoraTrainingRunnerTest {

    @Test
    void buildCommandLineRequiresModelPathsWhenNoTemplateIsConfigured() {
        WanLoraTrainingRunner runner = new WanLoraTrainingRunner(
                Mockito.mock(TaskRecordMapper.class),
                new ObjectMapper());
        ReflectionTestUtils.setField(runner, "commandTemplate", "");
        ReflectionTestUtils.setField(runner, "modelPathsJson", "");
        ReflectionTestUtils.setField(runner, "accelerateCommand", "accelerate");
        ReflectionTestUtils.setField(runner, "datasetRepeat", 5);
        ReflectionTestUtils.setField(runner, "datasetWorkers", 2);
        ReflectionTestUtils.setField(runner, "learningRate", "0.0001");

        WanLoraTrainingService.TrainingContext context = new WanLoraTrainingService.TrainingContext(
                1L,
                7L,
                11L,
                "run-1",
                Path.of("run"),
                "video_wan2_2_14B_i2v.json",
                "wan2.2",
                "continuity sample",
                8,
                1,
                true,
                List.of());

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                runner,
                "buildCommandLine",
                context,
                Path.of("."),
                Path.of("manifest.csv"),
                Path.of("dataset"),
                Path.of("output.safetensors")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NIREN_WAN22_MODEL_PATHS_JSON");
    }
}
