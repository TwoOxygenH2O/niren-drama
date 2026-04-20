package com.niren.drama.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.ai.impl.MockTtsProvider;
import com.niren.drama.ai.impl.OpenAiImageProvider;
import com.niren.drama.ai.impl.OpenAiTextProvider;
import com.niren.drama.ai.impl.OpenAiTtsProvider;
import com.niren.drama.entity.AiConfig;
import com.niren.drama.mapper.AiConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiProviderFactory {

    private final AiConfigMapper aiConfigMapper;

    public TextAiProvider getTextProvider(Long userId) {
        AiConfig config = getDefaultConfig(userId, "text");
        String baseUrl = config != null ? config.getBaseUrl() : "https://api.openai.com/v1";
        String apiKey  = config != null ? config.getApiKey()  : "";
        String model   = config != null ? config.getModel()   : "gpt-4o";
        return new OpenAiTextProvider(baseUrl, apiKey, model);
    }

    public ImageAiProvider getImageProvider(Long userId) {
        AiConfig config = getDefaultConfig(userId, "image");
        String baseUrl = config != null ? config.getBaseUrl() : "https://api.openai.com/v1";
        String apiKey  = config != null ? config.getApiKey()  : "";
        String model   = config != null ? config.getModel()   : "dall-e-3";
        return new OpenAiImageProvider(baseUrl, apiKey, model);
    }

    public TtsProvider getTtsProvider(Long userId) {
        AiConfig config = getDefaultConfig(userId, "tts");
        if (config != null && config.getApiKey() != null && !config.getApiKey().isBlank()) {
            String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                    ? config.getBaseUrl() : "https://api.openai.com/v1";
            String model = config.getModel() != null && !config.getModel().isBlank()
                    ? config.getModel() : "tts-1";
            return new OpenAiTtsProvider(baseUrl, config.getApiKey(), model);
        }
        // Fallback to mock when no TTS config is available
        return new MockTtsProvider();
    }

    private AiConfig getDefaultConfig(Long userId, String type) {
        return aiConfigMapper.selectOne(new LambdaQueryWrapper<AiConfig>()
                .eq(AiConfig::getUserId, userId)
                .eq(AiConfig::getConfigType, type)
                .eq(AiConfig::getIsDefault, 1)
                .last("LIMIT 1"));
    }
}
