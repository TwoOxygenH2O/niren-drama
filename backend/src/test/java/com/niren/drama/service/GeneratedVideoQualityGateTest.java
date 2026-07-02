package com.niren.drama.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeneratedVideoQualityGateTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsLocalGeneratedVideoWhenVisualAnalyzerReportsBlockingFinding() throws Exception {
        VisualQualityAnalyzer analyzer = mock(VisualQualityAnalyzer.class);
        GeneratedVideoQualityGate gate = new GeneratedVideoQualityGate(analyzer);
        ReflectionTestUtils.setField(gate, "uploadPath", tempDir.toString());
        ReflectionTestUtils.setField(gate, "publicBaseUrl", "http://localhost:8080/api/files");

        Path video = tempDir.resolve("generated-videos").resolve("bad.mp4");
        Files.createDirectories(video.getParent());
        Files.write(video, new byte[]{0, 1, 2, 3});

        when(analyzer.analyze(video, 8)).thenReturn(new VisualQualityReport(
                true,
                Map.of("averageColorfulness", 0.002d),
                List.of(new VisualQualityFinding(
                        "washed_gray_video",
                        "blocking",
                        "灰色视频不可用",
                        "抽帧缺少有效色彩和主体信息。",
                        "retryVideo",
                        Map.of()))));

        GeneratedVideoQualityGate.Result result = gate.evaluate(
                "http://localhost:8080/api/files/generated-videos/bad.mp4",
                8);

        assertThat(result.checked()).isTrue();
        assertThat(result.acceptable()).isFalse();
        assertThat(result.reason()).contains("washed_gray_video");
    }

    @Test
    void skipsRemoteVideoUrlInsteadOfBlockingWhenLocalFileCannotBeResolved() {
        VisualQualityAnalyzer analyzer = mock(VisualQualityAnalyzer.class);
        GeneratedVideoQualityGate gate = new GeneratedVideoQualityGate(analyzer);
        ReflectionTestUtils.setField(gate, "uploadPath", tempDir.toString());
        ReflectionTestUtils.setField(gate, "publicBaseUrl", "http://localhost:8080/api/files");

        GeneratedVideoQualityGate.Result result = gate.evaluate("https://cdn.example.com/video.mp4", 8);

        assertThat(result.checked()).isFalse();
        assertThat(result.acceptable()).isTrue();
        assertThat(result.reason()).isEqualTo("non_local_video");
    }
}
