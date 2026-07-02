package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.TtsAuditionAudioGenerator;
import com.niren.drama.ai.TtsAuditionGenerationRequest;
import com.niren.drama.ai.TtsAuditionGenerationResult;
import lombok.extern.slf4j.Slf4j;

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
        throw new UnsupportedOperationException("ComfyUI TTS submission is not implemented yet");
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
