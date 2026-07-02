package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.TtsAuditionAudioGenerator;
import com.niren.drama.ai.TtsAuditionGenerationRequest;
import com.niren.drama.ai.TtsAuditionGenerationResult;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Slf4j
public class ComfyUiTtsProvider implements TtsAuditionAudioGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiBaseUrl;
    private final String apiKey;
    private final String workflowFile;
    private final String extra;
    private final int maxPollAttempts;
    private final long pollIntervalMs;
    private final HttpClient httpClient;

    public ComfyUiTtsProvider(String apiBaseUrl, String apiKey, String workflowFile, String extra,
                              int maxPollAttempts, long pollIntervalMs) {
        this.apiBaseUrl = normalizeBaseUrl(apiBaseUrl);
        this.apiKey = apiKey;
        this.workflowFile = hasText(workflowFile) ? workflowFile : "tts_indextts2_audition.json";
        this.extra = extra;
        this.maxPollAttempts = maxPollAttempts > 0 ? maxPollAttempts : 900;
        this.pollIntervalMs = pollIntervalMs > 0 ? pollIntervalMs : 2000L;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public TtsAuditionGenerationResult generate(TtsAuditionGenerationRequest request) {
        try {
            JsonNode extraConfig = parseExtra(extra);
            ObjectNode workflow = resolveWorkflow(extraConfig);
            injectRuntimeValues(workflow, request, extraConfig);

            ObjectNode body = MAPPER.createObjectNode();
            body.set("prompt", workflow);
            String requestBody = MAPPER.writeValueAsString(body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/prompt"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(180));
            addAuthorization(requestBuilder);

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("ComfyUI TTS prompt failed: HTTP " + response.statusCode() + " - " + response.body());
            }

            String promptId = MAPPER.readTree(response.body()).path("prompt_id").asText(null);
            if (!hasText(promptId)) {
                throw new RuntimeException("ComfyUI TTS did not return prompt_id: " + response.body());
            }

            OutputInfo output = pollForResult(promptId);
            String outputUrl = buildOutputViewUrl(output);
            byte[] audio = downloadAudio(outputUrl);
            return new TtsAuditionGenerationResult(audio, promptId, outputUrl, workflowFile, null);
        } catch (Exception e) {
            throw new RuntimeException("ComfyUI TTS audition failed: " + e.getMessage(), e);
        }
    }

    private JsonNode parseExtra(String value) {
        if (!hasText(value)) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            log.warn("Invalid ComfyUI TTS extra JSON, using defaults: {}", e.getMessage());
            return MAPPER.createObjectNode();
        }
    }

    private ObjectNode resolveWorkflow(JsonNode extraConfig) {
        JsonNode inlineWorkflow = extraConfig != null ? extraConfig.path("workflow") : MAPPER.missingNode();
        if (inlineWorkflow.isObject()) {
            return (ObjectNode) inlineWorkflow.deepCopy();
        }

        String configuredWorkflowFile = extraConfig != null && hasText(extraConfig.path("workflowFile").asText(null))
                ? extraConfig.path("workflowFile").asText()
                : workflowFile;
        ObjectNode loaded = ComfyUiWorkflowLoader.loadWorkflow(apiBaseUrl, httpClient, configuredWorkflowFile);
        if (loaded == null) {
            throw new RuntimeException("未找到 ComfyUI TTS 工作流模板: " + configuredWorkflowFile);
        }
        return loaded;
    }

    private OutputInfo pollForResult(String promptId) throws Exception {
        String historyUrl = apiBaseUrl + "/history/" + promptId;
        int attempt = 0;
        while (true) {
            Thread.sleep(pollIntervalMs);
            attempt++;
            if (attempt > maxPollAttempts) {
                throw new RuntimeException("ComfyUI TTS 生成等待超时 (prompt_id=" + promptId + ")");
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(historyUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(30));
            addAuthorization(requestBuilder);
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("ComfyUI TTS history 查询失败: HTTP " + response.statusCode());
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode promptNode = root.path(promptId);
            if (promptNode.isMissingNode() || promptNode.isNull()) {
                continue;
            }

            OutputInfo outputInfo = findOutputInfo(promptNode.path("outputs"));
            if (outputInfo != null) {
                log.info("ComfyUI TTS 生成完成, filename={}, type={}, subfolder={}",
                        outputInfo.filename(), outputInfo.type(), outputInfo.subfolder());
                return outputInfo;
            }

            JsonNode status = promptNode.path("status");
            if (status.isObject() && "error".equalsIgnoreCase(status.path("status_str").asText(""))) {
                JsonNode messages = status.path("messages");
                String message = messages.isArray() && !messages.isEmpty() ? messages.toString() : "未知错误";
                throw new RuntimeException("ComfyUI TTS 任务执行失败: " + message);
            }
        }
    }

    private String buildOutputViewUrl(OutputInfo info) {
        StringBuilder sb = new StringBuilder(apiBaseUrl);
        sb.append("/view?filename=").append(encodeQueryParam(info.filename()));
        sb.append("&type=").append(hasText(info.type()) ? encodeQueryParam(info.type()) : "output");
        if (hasText(info.subfolder())) {
            sb.append("&subfolder=").append(encodeQueryParam(info.subfolder()));
        }
        return sb.toString();
    }

    private byte[] downloadAudio(String outputUrl) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(outputUrl))
                .GET()
                .timeout(Duration.ofSeconds(180));
        addAuthorization(requestBuilder);
        HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("ComfyUI TTS 音频下载失败: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private void addAuthorization(HttpRequest.Builder requestBuilder) {
        if (hasText(apiKey)) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
    }

    private static String encodeQueryParam(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static void injectRuntimeValues(ObjectNode workflow, TtsAuditionGenerationRequest request, JsonNode extraConfig) {
        JsonNode fieldMap = extraConfig != null ? extraConfig.path("fieldMap") : MAPPER.missingNode();
        if (fieldMap.isObject()) {
            putMapped(workflow, fieldMap, "text", request.text());
            putMapped(workflow, fieldMap, "speakerAudio", request.speakerReferenceAudio());
            putMapped(workflow, fieldMap, "emotionAudio", request.emotionReferenceAudio());
            putMapped(workflow, fieldMap, "emotionText", request.emotionText());
            putMapped(workflow, fieldMap, "useEmotionText", hasText(request.emotionText()));
            putMapped(workflow, fieldMap, "emotionVector", request.emotionVector());
            putMapped(workflow, fieldMap, "speed", request.speed());
            putMapped(workflow, fieldMap, "seed", request.seed());
            putMapped(workflow, fieldMap, "filenamePrefix", request.filenamePrefix());
            return;
        }
        injectHeuristically(workflow, request);
    }

    private static void putMapped(ObjectNode workflow, JsonNode fieldMap, String key, Object value) {
        if (value == null || !fieldMap.path(key).isArray()) {
            return;
        }
        for (JsonNode target : fieldMap.path(key)) {
            String nodeId = target.path("node").asText("");
            String input = target.path("input").asText("");
            JsonNode node = workflow.path(nodeId);
            JsonNode inputs = node.path("inputs");
            if (!node.isObject() || !inputs.isObject() || !hasText(input)) {
                continue;
            }
            putValue((ObjectNode) inputs, input, value);
        }
    }

    private static void injectHeuristically(ObjectNode workflow, TtsAuditionGenerationRequest request) {
        for (Iterator<Map.Entry<String, JsonNode>> it = workflow.fields(); it.hasNext(); ) {
            JsonNode inputs = it.next().getValue().path("inputs");
            if (!inputs.isObject()) {
                continue;
            }
            ObjectNode objectInputs = (ObjectNode) inputs;
            putFirstExisting(objectInputs, List.of("text", "prompt", "input_text"), request.text());
            putFirstExisting(objectInputs, List.of("speaker_audio", "spk_audio_prompt", "reference_audio", "audio"), request.speakerReferenceAudio());
            putFirstExisting(objectInputs, List.of("emo_text", "emotion_text"), request.emotionText());
            putFirstExisting(objectInputs, List.of("emotion_vector"), request.emotionVector());
            putFirstExisting(objectInputs, List.of("speech_speed", "speed"), request.speed());
            putFirstExisting(objectInputs, List.of("seed", "noise_seed"), request.seed());
            putFirstExisting(objectInputs, List.of("filename_prefix"), request.filenamePrefix());
        }
    }

    private static void putFirstExisting(ObjectNode inputs, List<String> names, Object value) {
        if (value == null) {
            return;
        }
        for (String name : names) {
            if (inputs.has(name)) {
                putValue(inputs, name, value);
                return;
            }
        }
    }

    private static void putValue(ObjectNode inputs, String input, Object value) {
        if (value instanceof String text) {
            if (hasText(text)) {
                inputs.put(input, text);
            }
        } else if (value instanceof Boolean bool) {
            inputs.put(input, bool);
        } else if (value instanceof Integer number) {
            inputs.put(input, number);
        } else if (value instanceof Long number) {
            inputs.put(input, number);
        } else if (value instanceof Double number) {
            inputs.put(input, number);
        } else if (value instanceof List<?> list) {
            ArrayNode array = MAPPER.createArrayNode();
            for (Object item : list) {
                if (item instanceof Number number) {
                    array.add(number.doubleValue());
                } else if (item instanceof String text) {
                    array.add(text);
                }
            }
            inputs.set(input, array);
        }
    }

    record OutputInfo(String filename, String type, String subfolder) {}

    static OutputInfo findOutputInfo(JsonNode outputs) {
        if (outputs == null || !outputs.isObject()) {
            return null;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = outputs.fields(); it.hasNext(); ) {
            JsonNode output = it.next().getValue();
            OutputInfo info = firstOutputInfo(output.path("audio"));
            if (info != null) {
                return info;
            }
            info = firstOutputInfo(output.path("audios"));
            if (info != null) {
                return info;
            }
            info = firstOutputInfo(output.path("sounds"));
            if (info != null) {
                return info;
            }
            info = firstAudioLikeOutputInfo(output.path("gifs"));
            if (info != null) {
                return info;
            }
            info = firstAudioLikeOutputInfo(output.path("videos"));
            if (info != null) {
                return info;
            }
            info = firstAudioLikeOutputInfo(output.path("images"));
            if (info != null) {
                return info;
            }
        }
        return null;
    }

    private static OutputInfo firstOutputInfo(JsonNode values) {
        if (values == null || values.isMissingNode() || values.isNull()) {
            return null;
        }
        if (values.isObject()) {
            String filename = values.path("filename").asText(null);
            return hasText(filename)
                    ? new OutputInfo(filename, values.path("type").asText("output"), values.path("subfolder").asText(""))
                    : null;
        }
        if (!values.isArray() || values.isEmpty()) {
            return null;
        }
        JsonNode first = values.get(0);
        String filename = first.path("filename").asText(null);
        if (!hasText(filename)) {
            return null;
        }
        return new OutputInfo(filename, first.path("type").asText("output"), first.path("subfolder").asText(""));
    }

    private static OutputInfo firstAudioLikeOutputInfo(JsonNode values) {
        OutputInfo info = firstOutputInfo(values);
        return info != null && isAudioFilename(info.filename()) ? info : null;
    }

    private static boolean isAudioFilename(String filename) {
        if (!hasText(filename)) {
            return false;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".wav")
                || lower.endsWith(".mp3")
                || lower.endsWith(".flac")
                || lower.endsWith(".ogg")
                || lower.endsWith(".m4a");
    }

    private static String normalizeBaseUrl(String value) {
        if (!hasText(value)) {
            return "http://127.0.0.1:8188";
        }
        String normalized = value.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
