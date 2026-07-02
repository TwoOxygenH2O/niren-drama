package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.TtsAuditionGenerationResult;
import com.niren.drama.ai.TtsAuditionGenerationRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void findsAudioOutputFromComfyHistory() throws Exception {
        ObjectNode history = (ObjectNode) objectMapper.readTree("""
            {
              "prompt-1": {
                "outputs": {
                  "25": {
                    "audios": [
                      {"filename": "voice.wav", "subfolder": "tts", "type": "output"}
                    ]
                  }
                }
              }
            }
            """);

        ComfyUiTtsProvider.OutputInfo info = ComfyUiTtsProvider.findOutputInfo(history.path("prompt-1").path("outputs"));

        assertThat(info).isNotNull();
        assertThat(info.filename()).isEqualTo("voice.wav");
        assertThat(info.subfolder()).isEqualTo("tts");
        assertThat(info.type()).isEqualTo("output");
    }

    @Test
    void submitsWorkflowPollsHistoryAndDownloadsAudio() throws Exception {
        byte[] wav = new MockTtsProvider().synthesize("试听", "narrator", 1.0f, 1.0f);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> promptBody = new AtomicReference<>();
        try {
            server.createContext("/prompt", exchange -> {
                promptBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = "{\"prompt_id\":\"prompt-1\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/history/prompt-1", exchange -> {
                byte[] body = """
                    {"prompt-1":{"outputs":{"25":{"audios":[{"filename":"voice.wav","subfolder":"tts","type":"output"}]}}}}
                    """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/view", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "audio/wav");
                exchange.sendResponseHeaders(200, wav.length);
                exchange.getResponseBody().write(wav);
                exchange.close();
            });
            server.start();

            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ComfyUiTtsProvider provider = new ComfyUiTtsProvider(baseUrl, "", "inline", """
                {"workflow":{"1":{"class_type":"IndexTTS2Simple","inputs":{"text":"","filename_prefix":""}}}}
                """, 5, 1);

            TtsAuditionGenerationResult result = provider.generate(new TtsAuditionGenerationRequest(
                    "旁白", "试听文本", "", "", "", List.of(), 1.0d, 1L, "audition_test"));

            assertThat(result.promptId()).isEqualTo("prompt-1");
            assertThat(result.outputUrl()).contains("/view?filename=voice.wav");
            assertThat(result.audio()).containsExactly(wav);
            assertThat(promptBody.get()).contains("试听文本");
        } finally {
            server.stop(0);
        }
    }
}
