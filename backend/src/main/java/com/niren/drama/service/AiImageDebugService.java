package com.niren.drama.service;

import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.AiResolvedConfig;
import com.niren.drama.ai.ImageAiProvider;
import com.niren.drama.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiImageDebugService {

    private static final String COS_SUBDIR = "ai-config-debug";

    private final AiProviderFactory aiProviderFactory;
    private final PublicAssetStorageService publicAssetStorageService;

    /**
     * Calls the user's resolved image provider once, then ensures the result is stored as a public asset
     * (COS when enabled, otherwise local {@code /api/files/...}).
     */
    public Map<String, Object> generateAndStore(Long userId, String prompt, String size) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException("请输入提示词");
        }
        AiResolvedConfig resolved = aiProviderFactory.resolveConfig(userId, "image");
        if (resolved.apiKey() == null || resolved.apiKey().isBlank()) {
            throw new BusinessException("未配置文生图 API Key：请在下方添加文生图配置并设为默认，或在服务端环境变量 AI_IMAGE_API_KEY 中配置");
        }
        ImageAiProvider provider = aiProviderFactory.getImageProvider(userId);
        String normalizedSize = (size != null && !size.isBlank()) ? size.trim() : ImageAiProvider.DEFAULT_SQUARE_SIZE;
        String providerUrl;
        try {
            providerUrl = provider.generateImage(prompt.trim(), normalizedSize, null);
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "文生图调用失败";
            throw new BusinessException(msg);
        }
        String imageUrl = publicAssetStorageService.ensurePublicUrl(providerUrl, COS_SUBDIR, "png");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imageUrl", imageUrl);
        out.put("providerUrl", providerUrl);
        out.put("size", normalizedSize);
        return out;
    }
}
