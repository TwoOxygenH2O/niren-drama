package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.TtsAuditionGenerationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComfyUiTtsProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void injectsRuntimeValuesUsingExplicitFieldMap() throws Exception {
        ObjectNode workflow = (ObjectNode) objectMapper.readTree("""
            {
              "10": {"class_type": "LoadAudio", "inputs": {"audio": ""}},
              "12": {"class_type": "IndexTTS2Advanced", "inputs": {"text": "", "emo_text": "", "use_emo_text": false, "speech_speed": 1.0, "seed": 1}},
              "20": {"class_type": "SaveAudio", "inputs": {"filename_prefix": "old"}}
            }
            """);
        ObjectNode extra = (ObjectNode) objectMapper.readTree("""
            {
              "fieldMap": {
                "text": [{"node": "12", "input": "text"}],
                "speakerAudio": [{"node": "10", "input": "audio"}],
                "emotionText": [{"node": "12", "input": "emo_text"}],
                "useEmotionText": [{"node": "12", "input": "use_emo_text"}],
                "speed": [{"node": "12", "input": "speech_speed"}],
                "seed": [{"node": "12", "input": "seed"}],
                "filenamePrefix": [{"node": "20", "input": "filename_prefix"}]
              }
            }
            """);

        TtsAuditionGenerationRequest request = new TtsAuditionGenerationRequest(
                "女儿",
                "你为什么骗我？",
                "daughter.wav",
                "",
                "委屈但清楚",
                List.of(0.0, 0.0, 0.55, 0.1, 0.0, 0.35, 0.0, 0.15),
                1.08d,
                12345L,
                "niren_tts_audition_1");

        ComfyUiTtsProvider.injectRuntimeValues(workflow, request, extra);

        assertThat(workflow.path("12").path("inputs").path("text").asText()).isEqualTo("你为什么骗我？");
        assertThat(workflow.path("10").path("inputs").path("audio").asText()).isEqualTo("daughter.wav");
        assertThat(workflow.path("12").path("inputs").path("emo_text").asText()).isEqualTo("委屈但清楚");
        assertThat(workflow.path("12").path("inputs").path("use_emo_text").asBoolean()).isTrue();
        assertThat(workflow.path("12").path("inputs").path("speech_speed").asDouble()).isEqualTo(1.08d);
        assertThat(workflow.path("12").path("inputs").path("seed").asLong()).isEqualTo(12345L);
        assertThat(workflow.path("20").path("inputs").path("filename_prefix").asText()).isEqualTo("niren_tts_audition_1");
    }

    @Test
    void injectsRuntimeValuesUsingHeuristicFallback() throws Exception {
        ObjectNode workflow = (ObjectNode) objectMapper.readTree("""
            {
              "1": {"class_type": "SomeIndexTtsNode", "inputs": {"input_text": "", "reference_audio": "", "emotion_text": "", "speed": 1.0}},
              "2": {"class_type": "SaveAudio", "inputs": {"filename_prefix": ""}}
            }
            """);
        TtsAuditionGenerationRequest request = new TtsAuditionGenerationRequest(
                "旁白",
                "她终于看清了真相。",
                "narrator.wav",
                "",
                "低沉克制",
                List.of(),
                0.95d,
                99L,
                "audition_narrator");

        ComfyUiTtsProvider.injectRuntimeValues(workflow, request, objectMapper.createObjectNode());

        assertThat(workflow.path("1").path("inputs").path("input_text").asText()).isEqualTo("她终于看清了真相。");
        assertThat(workflow.path("1").path("inputs").path("reference_audio").asText()).isEqualTo("narrator.wav");
        assertThat(workflow.path("1").path("inputs").path("emotion_text").asText()).isEqualTo("低沉克制");
        assertThat(workflow.path("1").path("inputs").path("speed").asDouble()).isEqualTo(0.95d);
        assertThat(workflow.path("2").path("inputs").path("filename_prefix").asText()).isEqualTo("audition_narrator");
    }
}
