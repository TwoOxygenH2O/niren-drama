package com.niren.drama.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.ai.impl.AliyunImageProvider;
import com.niren.drama.ai.impl.AliyunTtsProvider;
import com.niren.drama.ai.impl.ExternalImageProvider;
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

    @Value("${niren.ai.image.provider:aliyun}")
    private String defaultImageProvider;

    @Value("${niren.ai.image.base-url:https://dashscope.aliyuncs.com/api/v1}")
    private String defaultImageBaseUrl;

    @Value("${niren.ai.image.api-key:}")
    private String defaultImageApiKey;

    @Value("${niren.ai.image.model:qwen-image-2.0-pro}")
    private String defaultImageModel;

    @Value("${niren.ai.video.provider:aliyun}")
    private String defaultVideoProvider;

    @Value("${niren.ai.video.base-url:https://dashscope.aliyuncs.com/api/v1}")
    private String defaultVideoBaseUrl;

    @Value("${niren.ai.video.api-key:}")
    private String defaultVideoApiKey;

    @Value("${niren.ai.video.model:wan2.6-t2v}")
    private String defaultVideoModel;

    @Value("${niren.ai.tts.provider:aliyun}")
    private String defaultTtsProvider;

    @Value("${niren.ai.tts.base-url:https://dashscope.aliyuncs.com/api/v1}")
    private String defaultTtsBaseUrl;

    @Value("${niren.ai.tts.api-key:}")
    private String defaultTtsApiKey;

    @Value("${niren.ai.tts.model:qwen3-tts-flash}")
    private String defaultTtsModel;

    @Value("${niren.upload.path:./uploads}")
    private String uploadPath;

    @Value("${niren.upload.base-url:http://localhost:8080/api/files}")
    private String uploadBaseUrl;

    public TextAiProvider getTextProvider(Long userId) {
        AiResolvedConfig resolved = resolveConfig(userId, "text");

        // All text providers use OpenAI-compatible API (DeepSeek, Qianwen, Doubao, etc.)
        return new OpenAiTextProvider(resolved.baseUrl(), resolved.apiKey(), resolved.model(), defaultTextMaxTokens);
    }

    public ImageAiProvider getImageProvider(Long userId) {
        AiResolvedConfig resolved = resolveConfig(userId, "image");
        String provider = resolved.provider();
        String baseUrl = resolved.baseUrl();
        String apiKey = resolved.apiKey();
        String model = resolved.model();

        if (isAliyunProvider(provider)) {
            return new AliyunImageProvider(baseUrl, apiKey, model, provider, uploadPath, uploadBaseUrl);
        }

        if ("custom".equalsIgnoreCase(provider) || "external".equalsIgnoreCase(provider)) {
            return new ExternalImageProvider(baseUrl, apiKey, model, provider, uploadPath, uploadBaseUrl);
        }

        return new OpenAiImageProvider(baseUrl, apiKey, model, uploadPath, uploadBaseUrl);
    }

    public TtsProvider getTtsProvider(Long userId) {
        AiResolvedConfig resolved = resolveConfig(userId, "tts");
        String provider = resolved.provider();
        String apiKey = resolved.apiKey();
        if (!hasText(apiKey)) {
            return new MockTtsProvider();
        }
        if (isAliyunProvider(provider)) {
            return new AliyunTtsProvider(resolved.baseUrl(), apiKey, resolved.model(), provider);
        }
        return new OpenAiTtsProvider(resolved.baseUrl(), apiKey, resolved.model(), provider);
    }

    public AiResolvedConfig resolveConfig(Long userId, String configType) {
        AiConfig config = getDefaultConfig(userId, configType);
        String provider = firstNonBlank(config != null ? config.getProvider() : null, getDefaultProvider(configType));
        String baseUrl = firstNonBlank(config != null ? config.getBaseUrl() : null, getConfiguredDefaultBaseUrl(configType));
        String apiKey = firstNonBlank(config != null ? config.getApiKey() : null, getConfiguredDefaultApiKey(configType));
        String model = firstNonBlank(config != null ? config.getModel() : null, getConfiguredDefaultModel(configType));

        if (!hasText(baseUrl)) {
            baseUrl = getDefaultBaseUrl(provider, configType);
        }
        if (!hasText(model)) {
            model = getDefaultModel(provider, configType);
        }

        return new AiResolvedConfig(
                configType,
                provider,
                baseUrl,
                apiKey,
                model,
                config != null ? config.getExtra() : null);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String primary, String fallback) {
        return hasText(primary) ? primary : fallback;
    }

    private boolean isAliyunProvider(String provider) {
        if (!hasText(provider)) {
            return false;
        }
        String normalized = provider.trim().toLowerCase();
        return "aliyun".equals(normalized)
                || "qianwen".equals(normalized)
                || "dashscope".equals(normalized)
                || "wanx".equals(normalized)
                || "cosyvoice".equals(normalized);
    }

    private String getDefaultProvider(String configType) {
        return switch (configType) {
            case "image" -> defaultImageProvider;
            case "video" -> defaultVideoProvider;
            case "tts" -> defaultTtsProvider;
            case "text" -> defaultTextProvider;
            default -> defaultTextProvider;
        };
    }

    private String getConfiguredDefaultBaseUrl(String configType) {
        return switch (configType) {
            case "image" -> defaultImageBaseUrl;
            case "video" -> defaultVideoBaseUrl;
            case "tts" -> defaultTtsBaseUrl;
            case "text" -> defaultTextBaseUrl;
            default -> defaultTextBaseUrl;
        };
    }

    private String getConfiguredDefaultApiKey(String configType) {
        return switch (configType) {
            case "image" -> defaultImageApiKey;
            case "video" -> defaultVideoApiKey;
            case "tts" -> defaultTtsApiKey;
            case "text" -> defaultTextApiKey;
            default -> defaultTextApiKey;
        };
    }

    private String getConfiguredDefaultModel(String configType) {
        return switch (configType) {
            case "image" -> defaultImageModel;
            case "video" -> defaultVideoModel;
            case "tts" -> defaultTtsModel;
            case "text" -> defaultTextModel;
            default -> defaultTextModel;
        };
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
            case "custom" -> "text".equals(configType) ? "https://api.openai.com/v1" : "";
            case "deepseek" -> "https://api.deepseek.com";
            case "aliyun", "qianwen", "dashscope", "wanx", "cosyvoice" ->
                "text".equals(configType)
                    ? "https://dashscope.aliyuncs.com/compatible-mode/v1"
                    : "https://dashscope.aliyuncs.com/api/v1";
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
            case "sd" -> "http://localhost:7860";
            case "external" -> "";
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
                case "video" -> "sora";
                case "tts" -> "tts-1";
                default -> "gpt-4o";
            };
            case "custom" -> switch (configType) {
                case "text" -> "gpt-4o";
                case "image", "video", "tts" -> "";
                default -> "gpt-4o";
            };
            case "deepseek" -> "deepseek-chat";
            case "aliyun", "qianwen", "dashscope", "wanx", "cosyvoice" -> switch (configType) {
                case "text" -> "qwen-plus";
                case "image" -> "qwen-image-2.0-pro";
                case "video" -> "wan2.6-t2v";
                case "tts" -> "qwen3-tts-flash";
                default -> "qwen-plus";
            };
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
            case "kling" -> switch (configType) {
                case "image" -> "kolors-v1";
                case "video" -> "kling-v1";
                default -> "kling-v1";
            };
            case "jimeng" -> switch (configType) {
                case "video" -> "jimeng-video-v1";
                default -> "jimeng-2.1-pro";
            };
            case "runway" -> "gen-3";
            case "volcengine" -> "zh_female_qingxin";
            case "sd" -> "stable-diffusion-xl";
            case "external" -> "";
            default -> "gpt-4o";
        };
    }
}
