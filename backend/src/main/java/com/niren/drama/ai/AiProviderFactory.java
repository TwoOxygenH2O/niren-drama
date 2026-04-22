package com.niren.drama.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.ai.impl.DashScopeImageProvider;
import com.niren.drama.ai.impl.MockTtsProvider;
import com.niren.drama.ai.impl.OpenAiImageProvider;
import com.niren.drama.ai.impl.OpenAiTextProvider;
import com.niren.drama.ai.impl.OpenAiTtsProvider;
import com.niren.drama.entity.AiConfig;
import com.niren.drama.mapper.AiConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiProviderFactory {

    private final AiConfigMapper aiConfigMapper;

    @Value("${niren.ai.text.provider:openai}")
    private String defaultTextProvider;

    @Value("${niren.ai.text.base-url:https://api.openai.com/v1}")
    private String defaultTextBaseUrl;

    @Value("${niren.ai.text.api-key:}")
    private String defaultTextApiKey;

    @Value("${niren.ai.text.model:gpt-4o}")
    private String defaultTextModel;

    @Value("${niren.ai.text.max-tokens:16384}")
    private Integer defaultTextMaxTokens;

    @Value("${niren.ai.image.provider:openai}")
    private String defaultImageProvider;

    @Value("${niren.ai.image.base-url:https://api.openai.com/v1}")
    private String defaultImageBaseUrl;

    @Value("${niren.ai.image.api-key:}")
    private String defaultImageApiKey;

    @Value("${niren.ai.image.model:dall-e-3}")
    private String defaultImageModel;

    @Value("${niren.ai.tts.provider:volcengine}")
    private String defaultTtsProvider;

    @Value("${niren.ai.tts.base-url:}")
    private String defaultTtsBaseUrl;

    @Value("${niren.ai.tts.api-key:}")
    private String defaultTtsApiKey;

    @Value("${niren.ai.tts.model:}")
    private String defaultTtsModel;

    public TextAiProvider getTextProvider(Long userId) {
        AiConfig config = getDefaultConfig(userId, "text");
        String provider = hasText(config != null ? config.getProvider() : null) ? config.getProvider() : defaultTextProvider;
        String baseUrl = hasText(config != null ? config.getBaseUrl() : null) ? config.getBaseUrl() : defaultTextBaseUrl;
        String apiKey = hasText(config != null ? config.getApiKey() : null) ? config.getApiKey() : defaultTextApiKey;
        String model = hasText(config != null ? config.getModel() : null) ? config.getModel() : defaultTextModel;

        // Auto-fill base URL if missing based on provider
        if (!hasText(baseUrl)) {
            baseUrl = getDefaultBaseUrl(provider, "text");
        }
        if (!hasText(model)) {
            model = getDefaultModel(provider, "text");
        }

        // All text providers use OpenAI-compatible API (DeepSeek, Qianwen, Doubao, etc.)
        return new OpenAiTextProvider(baseUrl, apiKey, model, defaultTextMaxTokens);
    }

    public ImageAiProvider getImageProvider(Long userId) {
        AiConfig config = getDefaultConfig(userId, "image");
        String provider = hasText(config != null ? config.getProvider() : null) ? config.getProvider() : defaultImageProvider;
        String baseUrl = hasText(config != null ? config.getBaseUrl() : null) ? config.getBaseUrl() : defaultImageBaseUrl;
        String apiKey = hasText(config != null ? config.getApiKey() : null) ? config.getApiKey() : defaultImageApiKey;
        String model = hasText(config != null ? config.getModel() : null) ? config.getModel() : defaultImageModel;

        if (!hasText(baseUrl)) {
            baseUrl = getDefaultBaseUrl(provider, "image");
        }
        if (!hasText(model)) {
            model = getDefaultModel(provider, "image");
        }

        // DashScope (Alibaba Cloud Bailian) uses its own async image generation API.
        // The providers "dashscope", "qianwen", and "wanx" all refer to Alibaba Cloud's DashScope/Bailian service.
        if ("dashscope".equals(provider) || "qianwen".equals(provider) || "wanx".equals(provider)) {
            return new DashScopeImageProvider(baseUrl, apiKey, model);
        }

        return new OpenAiImageProvider(baseUrl, apiKey, model);
    }

    public TtsProvider getTtsProvider(Long userId) {
        AiConfig config = getDefaultConfig(userId, "tts");
        String provider = hasText(config != null ? config.getProvider() : null) ? config.getProvider() : defaultTtsProvider;
        String baseUrl = hasText(config != null ? config.getBaseUrl() : null) ? config.getBaseUrl() : defaultTtsBaseUrl;
        String apiKey = hasText(config != null ? config.getApiKey() : null) ? config.getApiKey() : defaultTtsApiKey;
        String model = hasText(config != null ? config.getModel() : null) ? config.getModel() : defaultTtsModel;

        if (!hasText(baseUrl)) {
            baseUrl = getDefaultBaseUrl(provider, "tts");
        }
        if (!hasText(model)) {
            model = getDefaultModel(provider, "tts");
        }
        if (hasText(apiKey)) {
            return new OpenAiTtsProvider(baseUrl, apiKey, model);
        }
        // Fallback to mock when no TTS config is available
        return new MockTtsProvider();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private AiConfig getDefaultConfig(Long userId, String type) {
        return aiConfigMapper.selectOne(new LambdaQueryWrapper<AiConfig>()
                .eq(AiConfig::getUserId, userId)
                .eq(AiConfig::getConfigType, type)
                .eq(AiConfig::getIsDefault, 1)
                .last("LIMIT 1"));
    }

    /**
     * Get the default base URL for a provider.
     */
    public static String getDefaultBaseUrl(String provider, String configType) {
        if (provider == null) return "https://api.openai.com/v1";
        return switch (provider.toLowerCase()) {
            case "openai" -> "https://api.openai.com/v1";
            case "deepseek" -> "https://api.deepseek.com";
            case "qianwen", "dashscope", "wanx", "cosyvoice" ->
                    "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "doubao" -> "https://ark.cn-beijing.volces.com/api/v3";
            case "seedream" -> "https://visual.volcengineapi.com";
            case "seedance" -> "https://open.volcengineapi.com";
            case "minimax" -> "https://api.minimax.chat/v1";
            case "moonshot" -> "https://api.moonshot.cn/v1";
            case "zhipu" -> "https://open.bigmodel.cn/api/paas/v4";
            case "baichuan" -> "https://api.baichuan-ai.com/v1";
            case "wenxin" -> "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop";
            case "kling" -> "https://api.klingai.com/v1";
            case "jimeng" -> "https://jimeng.jianying.com/v1";
            case "runway" -> "https://api.dev.runwayml.com/v1";
            case "volcengine" -> "https://openspeech.bytedance.com/api/v1";
            case "xunfei" -> "https://spark-api-open.xf-yun.com/v1";
            default -> "https://api.openai.com/v1";
        };
    }

    /**
     * Get the default model for a provider and config type.
     */
    public static String getDefaultModel(String provider, String configType) {
        if (provider == null) return "gpt-4o";
        return switch (provider.toLowerCase()) {
            case "openai" -> switch (configType) {
                case "text" -> "gpt-4o";
                case "image" -> "dall-e-3";
                case "tts" -> "tts-1";
                default -> "gpt-4o";
            };
            case "deepseek" -> "deepseek-chat";
            case "qianwen", "dashscope" -> switch (configType) {
                case "text" -> "qwen-plus";
                case "image" -> "wanx-v1";
                default -> "qwen-plus";
            };
            case "wanx" -> "wanx2.1-t2i-turbo";
            case "cosyvoice" -> "cosyvoice-v3-flash";
            case "xunfei" -> "x1";
            case "seedream" -> "high_aes_general_v30l_zt2i";
            case "seedance" -> "seedance2.0-turbo";
            case "doubao" -> "doubao-pro-32k";
            case "minimax" -> switch (configType) {
                case "tts" -> "speech-01-turbo";
                default -> "abab6.5s-chat";
            };
            case "moonshot" -> "moonshot-v1-8k";
            case "zhipu" -> "glm-4";
            case "baichuan" -> "Baichuan4";
            case "wenxin" -> "ernie-4.0-8k";
            case "kling" -> "kling-v1";
            case "jimeng" -> "jimeng-2.1-pro";
            case "runway" -> "gen-3";
            default -> "gpt-4o";
        };
    }
}
