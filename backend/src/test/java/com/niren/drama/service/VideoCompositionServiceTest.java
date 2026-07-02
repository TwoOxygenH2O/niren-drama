package com.niren.drama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.mapper.StoryboardMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VideoCompositionServiceTest {

    @Test
    void dynamicRenderPlanKeepsNarrationDurationWhenGeneratedVideoIsShorter() throws Exception {
        VideoCompositionService service = new VideoCompositionService(
                null, null, null, null, null, null, null, new ObjectMapper(), null, null);
        ReflectionTestUtils.setField(service, "composeMaxShotDurationSeconds", 10.0d);
        ReflectionTestUtils.setField(service, "composeVoiceDrivenSyncEnabled", false);

        Storyboard shot = new Storyboard();
        shot.setDuration(10);

        Method buildPlan = VideoCompositionService.class.getDeclaredMethod(
                "buildShotRenderPlan",
                Storyboard.class,
                double.class,
                double.class,
                double.class);
        buildPlan.setAccessible(true);

        Object renderPlan = buildPlan.invoke(service, shot, 0.0d, 0.45d, 2.0625d);
        Method contentDuration = renderPlan.getClass().getDeclaredMethod("contentDuration");
        Method clipDuration = renderPlan.getClass().getDeclaredMethod("clipDuration");
        contentDuration.setAccessible(true);
        clipDuration.setAccessible(true);

        assertEquals(10.0d, (double) contentDuration.invoke(renderPlan), 0.001d);
        assertEquals(10.45d, (double) clipDuration.invoke(renderPlan), 0.001d);
    }

    @Test
    void dynamicShotFilterUsesStableFrameInterpolationInsteadOfMotionCompensationJitter() throws Exception {
        VideoCompositionService service = new VideoCompositionService(
                null, null, null, null, null, null, null, new ObjectMapper(), null, null);
        ReflectionTestUtils.setField(service, "composeMaxShotDurationSeconds", 10.0d);
        ReflectionTestUtils.setField(service, "composeVoiceDrivenSyncEnabled", false);

        Storyboard shot = new Storyboard();
        shot.setDuration(8);

        Method buildPlan = VideoCompositionService.class.getDeclaredMethod(
                "buildShotRenderPlan",
                Storyboard.class,
                double.class,
                double.class,
                double.class);
        buildPlan.setAccessible(true);
        Object renderPlan = buildPlan.invoke(service, shot, 0.0d, 0.0d, 4.0d);

        Method buildFilter = VideoCompositionService.class.getDeclaredMethod(
                "buildDynamicShotFilter",
                Storyboard.class,
                renderPlan.getClass(),
                double.class);
        buildFilter.setAccessible(true);

        String filter = (String) buildFilter.invoke(service, shot, renderPlan, 4.0d);

        assertTrue(filter.contains("setpts=2.000*PTS"), filter);
        assertTrue(filter.contains("framerate=fps=25"), filter);
        assertTrue(!filter.contains("minterpolate"), filter);
        assertTrue(filter.contains("tpad=stop_mode=clone:stop_duration=0.600"), filter);
        assertTrue(!filter.contains("stop_duration=8.000"), filter);
    }

    @Test
    void finalCompositionFilterPadsFinalAudioToFullVideoDuration() throws Exception {
        VideoCompositionService service = new VideoCompositionService(
                null, null, null, null, null, null, null, new ObjectMapper(), null, null);

        Class<?> shotSegmentClass = nestedClass("ShotSegment");
        Constructor<?> shotSegmentConstructor = shotSegmentClass.getDeclaredConstructor(
                java.nio.file.Path.class, double.class, Storyboard.class);
        shotSegmentConstructor.setAccessible(true);
        Object segment = shotSegmentConstructor.newInstance(Paths.get("shot_1.mp4"), 8.0d, new Storyboard());

        Class<?> runtimeOptionsClass = nestedClass("ComposeRuntimeOptions");
        Constructor<?> runtimeOptionsConstructor = runtimeOptionsClass.getDeclaredConstructor(
                boolean.class, double.class, boolean.class, boolean.class, double.class);
        runtimeOptionsConstructor.setAccessible(true);
        Object runtimeOptions = runtimeOptionsConstructor.newInstance(true, 0.22d, true, false, 0.16d);

        Method buildFilter = VideoCompositionService.class.getDeclaredMethod(
                "buildFinalCompositionFilter",
                List.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                runtimeOptionsClass);
        buildFilter.setAccessible(true);

        String filter = (String) buildFilter.invoke(
                service,
                List.of(segment),
                -1, -1, -1, -1, -1, -1,
                runtimeOptions);

        assertTrue(
                filter.contains("[a0]apad=whole_dur=8.000,atrim=duration=8.000,asetpts=PTS-STARTPTS[aout]"),
                filter);
    }

    @Test
    void prepareDynamicVideoTaskExplicitlyClearsOldVideoColumns() throws Exception {
        StoryboardMapper storyboardMapper = mock(StoryboardMapper.class);
        VideoCompositionService service = new VideoCompositionService(
                null, storyboardMapper, null, null, null, null, null, new ObjectMapper(), null, null);

        Storyboard shot = new Storyboard();
        shot.setId(101L);
        shot.setVideoUrl("http://localhost:8080/api/files/generated-videos/old.mp4");
        shot.setVideoTaskId("old-task");
        shot.setVideoTaskStatusUrl("http://127.0.0.1:8188/history/old-task");
        shot.setVideoTaskProvider("comfyui");
        shot.setVideoTaskStatus("success");

        Method prepare = VideoCompositionService.class.getDeclaredMethod(
                "prepareShotForDynamicVideoTask", Storyboard.class, Long.class);
        prepare.setAccessible(true);
        prepare.invoke(service, shot, 202L);

        assertNull(shot.getVideoUrl());
        assertNull(shot.getVideoTaskId());
        assertNull(shot.getVideoTaskStatusUrl());
        assertNull(shot.getVideoTaskProvider());
        assertNull(shot.getVideoTaskStatus());
        assertEquals(202L, shot.getVideoTaskRecordId());
        verify(storyboardMapper).update(isNull(), any());
    }

    private static Class<?> nestedClass(String simpleName) {
        return Arrays.stream(VideoCompositionService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow();
    }
}
