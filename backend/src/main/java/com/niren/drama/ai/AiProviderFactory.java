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
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiProviderFactory {

    private final AiConfigMapper aiConfigMapper;

    public TextAiProvider getTextProvider(Long userId) {
        AiConfig config = getDefaultConfig(userId, "text");
        String provider = config != null ? config.getProvider() : "openai";
        String baseUrl = config != null ? config.getBaseUrl() : "https://api.openai.com/v1";
        String apiKey  = config != null ? config.getApiKey()  : "";
        String model   = config != null ? config.getModel()   : "gpt-4o";

        // Auto-fill base URL if missing based on provider
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = getDefaultBaseUrl(provider, "text");
        }
        if (model == null || model.isBlank()) {
            model = getDefaultModel(provider, "text");
        }

        // All text providers use OpenAI-compatible API (DeepSeek, Qianwen, Doubao, etc.)
        return new OpenAiTextProvider(baseUrl, apiKey, model);
    }

    public ImageAiProvider getImageProvider(Long userId) {
        AiConfig config = getDefaultConfig(userId, "image");
        String provider = config != null ? config.getProvider() : "openai";
        String baseUrl = config != null ? config.getBaseUrl() : "https://api.openai.com/v1";
        String apiKey  = config != null ? config.getApiKey()  : "";
        String model   = config != null ? config.getModel()   : "dall-e-3";

        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = getDefaultBaseUrl(provider, "image");
        }
        if (model == null || model.isBlank()) {
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
        if (config != null && config.getApiKey() != null && !config.getApiKey().isBlank()) {
            String provider = config.getProvider() != null ? config.getProvider().toLowerCase() : "openai";
            String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                    ? config.getBaseUrl() : getDefaultBaseUrl(provider, "tts");
            String model = config.getModel() != null && !config.getModel().isBlank()
                    ? config.getModel() : getDefaultModel(provider, "tts");
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
