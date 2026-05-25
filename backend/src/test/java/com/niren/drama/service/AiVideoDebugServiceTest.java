package com.niren.drama.service;

import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.VideoAiProvider;
import com.niren.drama.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiVideoDebugServiceTest {

    private final AiProviderFactory aiProviderFactory = mock(AiProviderFactory.class);
    private final VideoAiProvider videoAiProvider = mock(VideoAiProvider.class);
    private final AiVideoDebugService service = new AiVideoDebugService(aiProviderFactory);

    @Test
    void generateImageToVideoRequiresImageUrl() {
        assertThatThrownBy(() -> service.generateImageToVideo(1L, " ", "镜头缓慢推进", 5, "720x1280", "standard", false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请输入图片 URL");
    }

    @Test
    void generateImageToVideoUsesDefaultsAndReturnsVideoUrl() {
        when(aiProviderFactory.getVideoProvider(1L)).thenReturn(videoAiProvider);
        when(videoAiProvider.generateVideoFromImage("https://example.com/a.png", "镜头缓慢推进", 5, "720x1280", "standard", false))
                .thenReturn("/api/files/generated-videos/demo.mp4");

        Map<String, Object> result = service.generateImageToVideo(1L, " https://example.com/a.png ", "镜头缓慢推进", null, null, null, null);

        assertThat(result).containsEntry("videoUrl", "/api/files/generated-videos/demo.mp4");
        assertThat(result).containsEntry("duration", 5);
        assertThat(result).containsEntry("resolution", "720x1280");
        assertThat(result).containsEntry("quality", "standard");
        assertThat(result).containsEntry("withSound", false);
        verify(videoAiProvider).generateVideoFromImage("https://example.com/a.png", "镜头缓慢推进", 5, "720x1280", "standard", false);
    }
}
