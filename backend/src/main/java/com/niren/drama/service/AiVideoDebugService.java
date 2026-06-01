package com.niren.drama.service;

import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.VideoAiProvider;
import com.niren.drama.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiVideoDebugService {

    private static final int DEFAULT_DURATION = 5;
    private static final String DEFAULT_RESOLUTION = "720x1280";
    private static final String DEFAULT_QUALITY = "standard";

    private final AiProviderFactory aiProviderFactory;

    public Map<String, Object> generateImageToVideo(Long userId, String imageUrl, List<String> referenceImageUrls,
                                                    String prompt, Integer duration, String resolution,
                                                    String quality, Boolean withSound) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new BusinessException("请输入图片 URL");
        }
        int normalizedDuration = clampDuration(duration != null && duration > 0 ? duration : DEFAULT_DURATION);
        String normalizedResolution = resolution != null && !resolution.isBlank() ? resolution.trim() : DEFAULT_RESOLUTION;
        String normalizedQuality = quality != null && !quality.isBlank() ? quality.trim() : DEFAULT_QUALITY;
        boolean normalizedWithSound = Boolean.TRUE.equals(withSound);
        String normalizedPrompt = prompt != null ? prompt.trim() : "";

        VideoAiProvider provider = aiProviderFactory.getVideoProvider(userId);
        String videoUrl;
        try {
            videoUrl = provider.generateVideoFromImage(
                    imageUrl.trim(),
                    normalizeReferences(referenceImageUrls),
                    normalizedPrompt,
                    normalizedDuration,
                    normalizedResolution,
                    normalizedQuality,
                    normalizedWithSound);
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "图生视频调用失败";
            throw new BusinessException(msg);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videoUrl", videoUrl);
        out.put("providerUrl", videoUrl);
        out.put("duration", normalizedDuration);
        out.put("resolution", normalizedResolution);
        out.put("quality", normalizedQuality);
        out.put("withSound", normalizedWithSound);
        out.put("referenceCount", normalizeReferences(referenceImageUrls).size());
        return out;
    }

    private List<String> normalizeReferences(List<String> referenceImageUrls) {
        if (referenceImageUrls == null) {
            return List.of();
        }
        return referenceImageUrls.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .distinct()
                .limit(6)
                .toList();
    }

    private int clampDuration(int duration) {
        return Math.min(Math.max(duration, 3), 10);
    }
}
