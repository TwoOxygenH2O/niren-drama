package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.TtsProvider;
import com.niren.drama.ai.VoiceInfo;
import com.niren.drama.ai.trace.AiTraceSupport;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
public class AliyunTtsProvider implements TtsProvider {

    private static final List<VoiceCatalogEntry> VOICE_CATALOG = List.of(
            voice("Cherry", "芊悦", "female", "zh-CN", "阳光积极、亲切自然小姐姐", "qwen3-tts-instruct-flash", "qwen3-tts-flash", "qwen-tts"),
            voice("Serena", "苏瑶", "female", "zh-CN", "温柔小姐姐", "qwen3-tts-instruct-flash", "qwen3-tts-flash", "qwen-tts"),
            voice("Ethan", "晨煦", "male", "zh-CN", "标准普通话，阳光温暖有活力", "qwen3-tts-instruct-flash", "qwen3-tts-flash", "qwen-tts"),
            voice("Chelsie", "千雪", "female", "zh-CN", "二次元虚拟女友", "qwen3-tts-instruct-flash", "qwen3-tts-flash", "qwen-tts"),
            voice("Momo", "茉兔", "female", "zh-CN", "撒娇搞怪，逗你开心", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Vivian", "十三", "female", "zh-CN", "拽拽的、可爱的小暴躁", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Moon", "月白", "male", "zh-CN", "率性帅气的月白", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Maia", "四月", "female", "zh-CN", "知性与温柔的碰撞", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Kai", "凯", "male", "zh-CN", "耳朵的一场SPA", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Nofish", "不吃鱼", "male", "zh-CN", "不会翘舌音的设计师", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Bella", "萌宝", "female", "zh-CN", "喝酒不打醉拳的小萝莉", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Jennifer", "詹妮弗", "female", "zh-CN", "品牌级、电影质感般美语女声", "qwen3-tts-flash"),
            voice("Ryan", "甜茶", "male", "zh-CN", "节奏拉满，戏感炸裂，真实与张力共舞", "qwen3-tts-flash"),
            voice("Katerina", "卡捷琳娜", "female", "zh-CN", "御姐音色，韵律回味十足", "qwen3-tts-flash"),
            voice("Aiden", "艾登", "male", "zh-CN", "精通厨艺的美语大男孩", "qwen3-tts-flash"),
            voice("Eldric Sage", "沧明子", "male", "zh-CN", "沉稳睿智的老者，沧桑如松却心明如镜", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Mia", "乖小妹", "female", "zh-CN", "温顺如春水，乖巧如初雪", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Mochi", "沙小弥", "male", "zh-CN", "聪明伶俐的小大人，童真未泯却早慧如禅", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Bellona", "燕铮莺", "female", "zh-CN", "声音洪亮，吐字清晰，人物鲜活，热血沸腾", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Vincent", "田叔", "male", "zh-CN", "独特沙哑烟嗓，道尽江湖豪情", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Bunny", "萌小姬", "female", "zh-CN", "萌属性爆棚的小萝莉", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Neil", "阿闻", "male", "zh-CN", "平直基线语调，字正腔圆的新闻主持人", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Elias", "墨讲师", "female", "zh-CN", "严谨又会讲故事，善于把复杂知识讲清楚", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Arthur", "徐大爷", "male", "zh-CN", "被岁月和旱烟浸泡过的质朴嗓音", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Nini", "邻家妹妹", "female", "zh-CN", "软糯黏甜的邻家妹妹音", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Seren", "小婉", "female", "zh-CN", "温和舒缓，适合助眠晚安", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Pip", "顽屁小孩", "male", "zh-CN", "调皮捣蛋却充满童真", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Stella", "少女阿月", "female", "zh-CN", "甜腻迷糊少女与正义爆发的反差感", "qwen3-tts-instruct-flash", "qwen3-tts-flash"),
            voice("Bodega", "博德加", "male", "zh-CN", "热情的西班牙大叔", "qwen3-tts-flash"),
            voice("Sonrisa", "索尼莎", "female", "zh-CN", "热情开朗的拉美大姐", "qwen3-tts-flash"),
            voice("Alek", "阿列克", "male", "zh-CN", "冷暖并存的战斗民族气质", "qwen3-tts-flash"),
            voice("Dolce", "多尔切", "male", "zh-CN", "慵懒的意大利大叔", "qwen3-tts-flash"),
            voice("Sohee", "素熙", "female", "zh-CN", "温柔开朗、情绪丰富的韩国欧尼", "qwen3-tts-flash"),
            voice("Ono Anna", "小野杏", "female", "zh-CN", "鬼灵精怪的青梅竹马", "qwen3-tts-flash"),
            voice("Lenn", "莱恩", "male", "zh-CN", "理性是底色，叛逆藏在细节里", "qwen3-tts-flash"),
            voice("Emilien", "埃米尔安", "male", "zh-CN", "浪漫的法国大哥哥", "qwen3-tts-flash"),
            voice("Andre", "安德雷", "male", "zh-CN", "声音磁性，自然舒服、沉稳男生", "qwen3-tts-flash"),
            voice("Radio Gol", "拉迪奥·戈尔", "male", "zh-CN", "足球诗人式热情解说", "qwen3-tts-flash"),
            voice("Jada", "上海-阿珍", "female", "zh-CN-shanghai", "风风火火的沪上阿姐", "qwen3-tts-flash", "qwen-tts"),
            voice("Dylan", "北京-晓东", "male", "zh-CN-beijing", "北京胡同里长大的少年", "qwen3-tts-flash", "qwen-tts"),
            voice("Li", "南京-老李", "male", "zh-CN-nanjing", "耐心的瑜伽老师", "qwen3-tts-flash"),
            voice("Marcus", "陕西-秦川", "male", "zh-CN-shaanxi", "面宽话短，心实声沉的老陕味道", "qwen3-tts-flash"),
            voice("Roy", "闽南-阿杰", "male", "zh-CN-minnan", "诙谐直爽、市井活泼的台湾哥仔", "qwen3-tts-flash"),
            voice("Peter", "天津-李彼得", "male", "zh-CN-tianjin", "天津相声，专业捧哏", "qwen3-tts-flash"),
            voice("Sunny", "四川-晴儿", "female", "zh-CN-sichuan", "甜到你心里的川妹子", "qwen3-tts-flash", "qwen-tts"),
            voice("Eric", "四川-程川", "male", "zh-CN-sichuan", "跳脱市井的四川成都男子", "qwen3-tts-flash"),
            voice("Rocky", "粤语-阿强", "male", "zh-CN-yue", "幽默风趣的阿强，在线陪聊", "qwen3-tts-flash"),
            voice("Kiki", "粤语-阿清", "female", "zh-CN-yue", "甜美的港妹闺蜜", "qwen3-tts-flash")
    );

    private final String providerName;
    private final String apiBaseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AliyunTtsProvider(String baseUrl, String apiKey, String model, String providerName) {
        this.providerName = providerName != null && !providerName.isBlank() ? providerName : "aliyun";
        this.apiBaseUrl = normalizeApiBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = normalizeModel(model);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public byte[] synthesize(String text, String voiceId, float speed, float pitch) {
        return synthesize(text, voiceId, speed, pitch, null, null);
    }

    @Override
    public byte[] synthesize(String text,
                             String voiceId,
                             float speed,
                             float pitch,
                             String instruction,
                             String languageType) {
        String normalizedVoiceId = hasText(voiceId) ? voiceId.trim() : "Cherry";
        String normalizedLanguageType = hasText(languageType) ? languageType.trim() : resolveLanguageType(text);
        String normalizedInstruction = hasText(instruction) ? instruction.trim() : null;
        List<String> instructionFields = shouldSendInstruction(normalizedInstruction)
                ? List.of("prompt", "instruction", "")
                : List.of("");
        RuntimeException lastFailure = null;
        for (String instructionField : instructionFields) {
            try {
                return doSynthesize(text, normalizedVoiceId, speed, pitch, normalizedInstruction, normalizedLanguageType, instructionField);
            } catch (RuntimeException e) {
                lastFailure = e;
                if (!hasText(instructionField)) {
                    throw e;
                }
                log.warn("阿里云 TTS 使用指令字段'{}'请求失败，回退下一种字段写法: {}", instructionField, e.getMessage());
            }
        }
        throw lastFailure != null ? lastFailure : new RuntimeException("TTS synthesis failed: no request variant succeeded");
    }

    private byte[] doSynthesize(String text,
                                String voiceId,
                                float speed,
                                float pitch,
                                String instruction,
                                String languageType,
                                String instructionField) {
        String endpoint = apiBaseUrl + "/services/aigc/multimodal-generation/generation";
        log.debug("开始阿里云 TTS 合成: provider={}, model={}, voiceId={}, textLength={}, speed={}, pitch={}, languageType={}, instructionField={}, hasInstruction={}",
            providerName,
            model,
            voiceId,
            text != null ? text.length() : 0,
            speed,
            pitch,
            languageType,
            hasText(instructionField) ? instructionField : "none",
            hasText(instruction));
        String requestBody = null;
        HttpResponse<String> generationResponse = null;
        String generationResponseBody = null;
        String audioUrl = null;
        String error = null;
        Map<String, String> headers = AiTraceSupport.jsonHeaders(apiKey);

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);

            ObjectNode input = body.putObject("input");
            input.put("text", text);
            input.put("voice", voiceId);
            input.put("language_type", languageType);
            if (hasText(instruction) && hasText(instructionField)) {
                input.put(instructionField, instruction);
            }

            requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(180))
                    .build();

            generationResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            generationResponseBody = generationResponse.body();
            if (generationResponse.statusCode() >= 400) {
                error = "HTTP " + generationResponse.statusCode() + " - " + generationResponseBody;
                throw new RuntimeException(error);
            }

            JsonNode root = objectMapper.readTree(generationResponseBody);
            audioUrl = firstText(
                    root.path("output").path("audio").path("url"),
                    root.path("output").path("audio").path("audio_url"),
                    root.path("output").path("audio").path("resource_url"),
                    root.path("output").path("audio_url"),
                    root.path("data").path("audio").path("url"),
                    root.path("data").path("audio").path("audio_url"),
                    root.path("audio").path("url"),
                    root.path("output").path("url"));
            if (audioUrl == null || audioUrl.isBlank()) {
                error = "阿里云 TTS 接口未返回音频地址: " + generationResponseBody;
                throw new RuntimeException(error);
            }

            AiTraceSupport.record(
                    "tts",
                    providerName,
                    "synthesize_speech",
                    "POST",
                    endpoint,
                    headers,
                    requestBody,
                    generationResponse.statusCode(),
                    generationResponse.headers().firstValue("Content-Type").orElse(null),
                    generationResponseBody,
                    generationResponseBody.length(),
                    true,
                    audioUrl,
                    null);

            HttpRequest downloadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(audioUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(180))
                    .build();
            HttpResponse<byte[]> audioResponse = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (audioResponse.statusCode() >= 400) {
                throw new RuntimeException("音频下载失败: HTTP " + audioResponse.statusCode());
            }

            AiTraceSupport.record(
                    "tts",
                    providerName,
                    "download_audio",
                    "GET",
                    audioUrl,
                    Map.of(),
                    null,
                    audioResponse.statusCode(),
                    audioResponse.headers().firstValue("Content-Type").orElse(null),
                    null,
                    audioResponse.body() != null ? audioResponse.body().length : null,
                    audioResponse.body() != null && audioResponse.body().length > 0,
                    audioUrl,
                    null);
                    log.debug("阿里云 TTS 合成成功: provider={}, voiceId={}, audioUrl={}, audioSize={}",
                        providerName,
                        voiceId,
                        audioUrl,
                        audioResponse.body() != null ? audioResponse.body().length : 0);
            return audioResponse.body();
        } catch (Exception e) {
            if (error == null || error.isBlank()) {
                error = e.getMessage();
            }
            AiTraceSupport.record(
                    "tts",
                    providerName,
                    "synthesize_speech",
                    "POST",
                    endpoint,
                    headers,
                    requestBody,
                    generationResponse != null ? generationResponse.statusCode() : null,
                    generationResponse != null ? generationResponse.headers().firstValue("Content-Type").orElse(null) : null,
                    generationResponseBody,
                    generationResponseBody != null ? generationResponseBody.length() : null,
                    false,
                    audioUrl,
                    error);
            log.error("阿里云 TTS API 调用失败", e);
            throw new RuntimeException("TTS synthesis failed: " + error, e);
        }
    }

    @Override
    public List<VoiceInfo> listVoices() {
        List<VoiceInfo> voices = VOICE_CATALOG.stream()
                .filter(voice -> voice.supports(model))
                .map(VoiceCatalogEntry::toVoiceInfo)
                .toList();
        if (voices.isEmpty()) {
            voices = VOICE_CATALOG.stream().map(VoiceCatalogEntry::toVoiceInfo).toList();
        }
        log.debug("阿里云 TTS 音色加载完成: provider={}, count={}", providerName, voices.size());
        return voices;
    }

    private String resolveLanguageType(String text) {
        if (text == null || text.isBlank()) {
            return "Chinese";
        }
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(text.charAt(i));
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                return "Chinese";
            }
        }
        return "English";
    }

    private String firstText(JsonNode... nodes) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            String text = node.asText(null);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private String normalizeApiBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "https://dashscope.aliyuncs.com/api/v1";
        }
        String normalized = trimTrailingSlash(value.trim());
        String[] suffixes = new String[] {
                "/services/aigc/multimodal-generation/generation",
                "/services/aigc/multimodal-conversation/generation",
                "/api/v1"
        };
        boolean stripped;
        do {
            stripped = false;
            for (String suffix : suffixes) {
                if (normalized.endsWith(suffix)) {
                    normalized = trimTrailingSlash(normalized.substring(0, normalized.length() - suffix.length()));
                    stripped = true;
                    break;
                }
            }
        } while (stripped);
        return normalized + "/api/v1";
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String normalizeModel(String value) {
        if (value == null || value.isBlank()) {
            return "qwen3-tts-flash";
        }
        return value.trim();
    }

    private boolean shouldSendInstruction(String instruction) {
        return hasText(instruction) && lower(model).startsWith("qwen3-tts-instruct-flash");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static VoiceCatalogEntry voice(String voiceId,
                                           String name,
                                           String gender,
                                           String language,
                                           String description,
                                           String... supportedModelPrefixes) {
        return new VoiceCatalogEntry(voiceId, name, gender, language, description, List.of(supportedModelPrefixes));
    }

    private record VoiceCatalogEntry(String voiceId,
                                     String name,
                                     String gender,
                                     String language,
                                     String description,
                                     List<String> supportedModelPrefixes) {

        private boolean supports(String model) {
            if (supportedModelPrefixes == null || supportedModelPrefixes.isEmpty()) {
                return true;
            }
            String normalizedModel = model == null ? "" : model.trim().toLowerCase();
            for (String prefix : supportedModelPrefixes) {
                if (prefix != null && !prefix.isBlank() && normalizedModel.startsWith(prefix.toLowerCase())) {
                    return true;
                }
            }
            return false;
        }

        private VoiceInfo toVoiceInfo() {
            return new VoiceInfo(voiceId, name, gender, language, description);
        }
    }
}