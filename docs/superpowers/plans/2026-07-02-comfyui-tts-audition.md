# ComfyUI TTS Audition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an independent ComfyUI-driven TTS audition lane that generates role voice candidates for review without feeding them into video composition.

**Architecture:** Add a workflow-template-driven `ComfyUiTtsProvider` for ComfyUI audio outputs, then build `TtsAuditionService` and `TtsAuditionController` around `TaskRecord` results. Keep audition output isolated in `audios/audition/...`; do not update `Storyboard.audioUrl` or the existing `/videos/generate-audio/{projectId}` path.

**Tech Stack:** Spring Boot 3.2, Java 17, Maven, MyBatis-Plus mappers, Jackson, JUnit 5, AssertJ, Mockito, ComfyUI `/prompt`, `/history/{prompt_id}`, `/view`.

---

## File Structure

- Create `backend/src/main/java/com/niren/drama/dto/tts/TtsAuditionRequest.java`: request body for project-level audition jobs.
- Create `backend/src/main/java/com/niren/drama/dto/tts/TtsAuditionRoleOverride.java`: per-role speaker/emotion overrides.
- Create `backend/src/main/java/com/niren/drama/ai/TtsAuditionAudioGenerator.java`: small interface between service and ComfyUI provider.
- Create `backend/src/main/java/com/niren/drama/ai/TtsAuditionGenerationRequest.java`: provider input record.
- Create `backend/src/main/java/com/niren/drama/ai/TtsAuditionGenerationResult.java`: provider output record with audio bytes and ComfyUI metadata.
- Create `backend/src/main/java/com/niren/drama/ai/impl/ComfyUiTtsProvider.java`: workflow loading, field injection, prompt submission, polling, audio output detection, and audio download.
- Create `backend/src/main/java/com/niren/drama/ai/impl/ComfyUiTtsProviderFactory.java`: resolves ComfyUI TTS config per user and creates providers with audition poll settings.
- Create `backend/src/main/java/com/niren/drama/controller/TtsAuditionController.java`: `POST /tts-auditions/projects/{projectId}`.
- Create `backend/src/main/java/com/niren/drama/service/TtsAuditionService.java`: role selection, task creation, async candidate generation, storage, JSON result assembly.
- Modify `backend/src/main/resources/application.yml`: add `niren.ai.tts.workflow-file` and `niren.ai.tts.audition.*`.
- Modify `backend/src/main/java/com/niren/drama/entity/TaskRecord.java`: update task-type comment to include `TTS_AUDITION`.
- Test `backend/src/test/java/com/niren/drama/ai/impl/ComfyUiTtsProviderTest.java`.
- Test `backend/src/test/java/com/niren/drama/service/TtsAuditionServiceTest.java`.

---

### Task 1: ComfyUI TTS Provider Contract And Injection

**Files:**
- Create: `backend/src/main/java/com/niren/drama/ai/TtsAuditionAudioGenerator.java`
- Create: `backend/src/main/java/com/niren/drama/ai/TtsAuditionGenerationRequest.java`
- Create: `backend/src/main/java/com/niren/drama/ai/TtsAuditionGenerationResult.java`
- Create: `backend/src/main/java/com/niren/drama/ai/impl/ComfyUiTtsProvider.java`
- Test: `backend/src/test/java/com/niren/drama/ai/impl/ComfyUiTtsProviderTest.java`

- [ ] **Step 1: Write the failing explicit field-map injection test**

Create `backend/src/test/java/com/niren/drama/ai/impl/ComfyUiTtsProviderTest.java` with:

```java
package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.TtsAuditionGenerationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd backend
mvn -Dtest=ComfyUiTtsProviderTest#injectsRuntimeValuesUsingExplicitFieldMap test
```

Expected: compile failure because `TtsAuditionGenerationRequest` and `ComfyUiTtsProvider` do not exist.

- [ ] **Step 3: Add provider contract records and minimal injection implementation**

Create `backend/src/main/java/com/niren/drama/ai/TtsAuditionAudioGenerator.java`:

```java
package com.niren.drama.ai;

public interface TtsAuditionAudioGenerator {
    TtsAuditionGenerationResult generate(TtsAuditionGenerationRequest request);
}
```

Create `backend/src/main/java/com/niren/drama/ai/TtsAuditionGenerationRequest.java`:

```java
package com.niren.drama.ai;

import java.util.List;

public record TtsAuditionGenerationRequest(
        String roleName,
        String text,
        String speakerReferenceAudio,
        String emotionReferenceAudio,
        String emotionText,
        List<Double> emotionVector,
        Double speed,
        Long seed,
        String filenamePrefix
) {
}
```

Create `backend/src/main/java/com/niren/drama/ai/TtsAuditionGenerationResult.java`:

```java
package com.niren.drama.ai;

public record TtsAuditionGenerationResult(
        byte[] audio,
        String promptId,
        String outputUrl,
        String workflowFile,
        Double durationSeconds
) {
}
```

Create the first version of `backend/src/main/java/com/niren/drama/ai/impl/ComfyUiTtsProvider.java` with:

```java
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
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    @Override
    public TtsAuditionGenerationResult generate(TtsAuditionGenerationRequest request) {
        throw new UnsupportedOperationException("ComfyUI TTS submission is implemented in Task 2");
    }

    static void injectRuntimeValues(ObjectNode workflow, TtsAuditionGenerationRequest request, JsonNode extraConfig) {
        JsonNode fieldMap = extraConfig != null ? extraConfig.path("fieldMap") : MAPPER.missingNode();
        boolean usedExplicit = fieldMap.isObject();
        if (usedExplicit) {
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
            if (!node.isObject() || !hasText(input)) {
                continue;
            }
            putValue((ObjectNode) node.path("inputs"), input, value);
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

    @SuppressWarnings("unchecked")
    private static void putValue(ObjectNode inputs, String input, Object value) {
        if (value instanceof String text) {
            if (hasText(text)) inputs.put(input, text);
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
        if (!hasText(value)) return "http://127.0.0.1:8188";
        String normalized = value.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
cd backend
mvn -Dtest=ComfyUiTtsProviderTest#injectsRuntimeValuesUsingExplicitFieldMap test
```

Expected: test passes.

- [ ] **Step 5: Add heuristic injection test**

Append this test to `ComfyUiTtsProviderTest`:

```java
@Test
void injectsRuntimeValuesUsingHeuristicFallback() throws Exception {
    ObjectNode workflow = (ObjectNode) objectMapper.readTree("""
        {
          "1": {"class_type": "SomeIndexTtsNode", "inputs": {"input_text": "", "reference_audio": "", "emotion_text": "", "speed": 1.0}},
          "2": {"class_type": "SaveAudio", "inputs": {"filename_prefix": ""}}
        }
        """);
    TtsAuditionGenerationRequest request = new TtsAuditionGenerationRequest(
            "旁白", "她终于看清了真相。", "narrator.wav", "", "低沉克制", List.of(), 0.95d, 99L, "audition_narrator");

    ComfyUiTtsProvider.injectRuntimeValues(workflow, request, objectMapper.createObjectNode());

    assertThat(workflow.path("1").path("inputs").path("input_text").asText()).isEqualTo("她终于看清了真相。");
    assertThat(workflow.path("1").path("inputs").path("reference_audio").asText()).isEqualTo("narrator.wav");
    assertThat(workflow.path("1").path("inputs").path("emotion_text").asText()).isEqualTo("低沉克制");
    assertThat(workflow.path("1").path("inputs").path("speed").asDouble()).isEqualTo(0.95d);
    assertThat(workflow.path("2").path("inputs").path("filename_prefix").asText()).isEqualTo("audition_narrator");
}
```

- [ ] **Step 6: Run provider injection tests**

Run:

```bash
cd backend
mvn -Dtest=ComfyUiTtsProviderTest test
```

Expected: both tests pass.

- [ ] **Step 7: Commit Task 1**

```bash
git add backend/src/main/java/com/niren/drama/ai/TtsAuditionAudioGenerator.java \
        backend/src/main/java/com/niren/drama/ai/TtsAuditionGenerationRequest.java \
        backend/src/main/java/com/niren/drama/ai/TtsAuditionGenerationResult.java \
        backend/src/main/java/com/niren/drama/ai/impl/ComfyUiTtsProvider.java \
        backend/src/test/java/com/niren/drama/ai/impl/ComfyUiTtsProviderTest.java
git commit -m "feat: add comfyui tts workflow injection"
```

---

### Task 2: ComfyUI Audio Submission And Output Detection

**Files:**
- Modify: `backend/src/main/java/com/niren/drama/ai/impl/ComfyUiTtsProvider.java`
- Test: `backend/src/test/java/com/niren/drama/ai/impl/ComfyUiTtsProviderTest.java`

- [ ] **Step 1: Write failing audio output detection test**

Append:

```java
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
```

- [ ] **Step 2: Verify RED**

Run:

```bash
cd backend
mvn -Dtest=ComfyUiTtsProviderTest#findsAudioOutputFromComfyHistory test
```

Expected: compile failure because `OutputInfo` and `findOutputInfo` are not public/package-visible.

- [ ] **Step 3: Implement output detection**

In `ComfyUiTtsProvider`, add:

```java
record OutputInfo(String filename, String type, String subfolder) {}

static OutputInfo findOutputInfo(JsonNode outputs) {
    if (outputs == null || !outputs.isObject()) {
        return null;
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = outputs.fields(); it.hasNext(); ) {
        JsonNode output = it.next().getValue();
        OutputInfo info = firstOutputInfo(output.path("audio"));
        if (info != null) return info;
        info = firstOutputInfo(output.path("audios"));
        if (info != null) return info;
        info = firstOutputInfo(output.path("sounds"));
        if (info != null) return info;
        info = firstAudioLikeOutputInfo(output.path("gifs"));
        if (info != null) return info;
        info = firstAudioLikeOutputInfo(output.path("videos"));
        if (info != null) return info;
        info = firstAudioLikeOutputInfo(output.path("images"));
        if (info != null) return info;
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
    if (!hasText(filename)) return false;
    String lower = filename.toLowerCase();
    return lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".flac")
            || lower.endsWith(".ogg") || lower.endsWith(".m4a");
}
```

- [ ] **Step 4: Run output detection test**

Run:

```bash
cd backend
mvn -Dtest=ComfyUiTtsProviderTest#findsAudioOutputFromComfyHistory test
```

Expected: test passes.

- [ ] **Step 5: Write failing end-to-end fake ComfyUI server test**

Append a test using `com.sun.net.httpserver.HttpServer`:

```java
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
```

Add imports:

```java
import com.niren.drama.ai.TtsAuditionGenerationResult;
import com.niren.drama.ai.impl.MockTtsProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
```

- [ ] **Step 6: Verify RED**

Run:

```bash
cd backend
mvn -Dtest=ComfyUiTtsProviderTest#submitsWorkflowPollsHistoryAndDownloadsAudio test
```

Expected: fails because `generate` throws `UnsupportedOperationException`.

- [ ] **Step 7: Implement provider submission, polling, URL building, and download**

Replace `generate` with full implementation:

```java
@Override
public TtsAuditionGenerationResult generate(TtsAuditionGenerationRequest request) {
    try {
        JsonNode extraConfig = parseExtra(extra);
        ObjectNode workflow = resolveWorkflow(extraConfig);
        injectRuntimeValues(workflow, request, extraConfig);
        String requestBody = MAPPER.writeValueAsString(Map.of("prompt", workflow));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/prompt"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(180));
        if (hasText(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
```

Also add helper methods:

```java
private JsonNode parseExtra(String value) {
    if (!hasText(value)) return MAPPER.createObjectNode();
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
    ObjectNode loaded = ComfyUiWorkflowLoader.loadWorkflow(apiBaseUrl, httpClient,
            extraConfig != null && hasText(extraConfig.path("workflowFile").asText(null))
                    ? extraConfig.path("workflowFile").asText()
                    : workflowFile);
    if (loaded == null) {
        throw new RuntimeException("未找到 ComfyUI TTS 工作流模板: " + workflowFile);
    }
    return loaded;
}
```

Use the existing patterns from `ComfyUiVideoProvider` for `pollForResult`, `buildOutputViewUrl`, and queue status, but with TTS wording and `findOutputInfo` from this class.

- [ ] **Step 8: Run all provider tests**

Run:

```bash
cd backend
mvn -Dtest=ComfyUiTtsProviderTest test
```

Expected: all provider tests pass.

- [ ] **Step 9: Commit Task 2**

```bash
git add backend/src/main/java/com/niren/drama/ai/impl/ComfyUiTtsProvider.java \
        backend/src/test/java/com/niren/drama/ai/impl/ComfyUiTtsProviderTest.java
git commit -m "feat: run comfyui tts audition workflows"
```

---

### Task 3: Audition DTOs, Factory, And Service

**Files:**
- Create: `backend/src/main/java/com/niren/drama/dto/tts/TtsAuditionRequest.java`
- Create: `backend/src/main/java/com/niren/drama/dto/tts/TtsAuditionRoleOverride.java`
- Create: `backend/src/main/java/com/niren/drama/ai/impl/ComfyUiTtsProviderFactory.java`
- Create: `backend/src/main/java/com/niren/drama/service/TtsAuditionService.java`
- Modify: `backend/src/main/java/com/niren/drama/entity/TaskRecord.java`
- Test: `backend/src/test/java/com/niren/drama/service/TtsAuditionServiceTest.java`

- [ ] **Step 1: Write failing service partial-success test**

Create `backend/src/test/java/com/niren/drama/service/TtsAuditionServiceTest.java`:

```java
package com.niren.drama.service;

import com.niren.drama.ai.TtsAuditionAudioGenerator;
import com.niren.drama.ai.TtsAuditionGenerationResult;
import com.niren.drama.ai.impl.ComfyUiTtsProviderFactory;
import com.niren.drama.ai.impl.MockTtsProvider;
import com.niren.drama.dto.tts.TtsAuditionRequest;
import com.niren.drama.entity.Character;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.StoryboardMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import com.niren.drama.service.storage.StoredAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TtsAuditionServiceTest {

    private final CharacterMapper characterMapper = mock(CharacterMapper.class);
    private final StoryboardMapper storyboardMapper = mock(StoryboardMapper.class);
    private final TaskRecordMapper taskRecordMapper = mock(TaskRecordMapper.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final PublicAssetStorageService storageService = mock(PublicAssetStorageService.class);
    private final ComfyUiTtsProviderFactory providerFactory = mock(ComfyUiTtsProviderFactory.class);
    private final ObjectProvider<TtsAuditionService> selfProvider = mock(ObjectProvider.class);
    private final AtomicLong ids = new AtomicLong(100);
    private TtsAuditionService service;

    @BeforeEach
    void setUp() {
        service = new TtsAuditionService(characterMapper, storyboardMapper, taskRecordMapper, projectService,
                storageService, providerFactory, selfProvider);
        when(selfProvider.getObject()).thenReturn(service);
        when(projectService.getProject(7L, 9L)).thenReturn(project());
        when(taskRecordMapper.insert(any(TaskRecord.class))).thenAnswer(invocation -> {
            TaskRecord task = invocation.getArgument(0);
            task.setId(ids.incrementAndGet());
            return 1;
        });
        when(taskRecordMapper.selectById(anyLong())).thenAnswer(invocation -> {
            TaskRecord task = new TaskRecord();
            task.setId(invocation.getArgument(0));
            task.setProjectId(9L);
            task.setUserId(7L);
            task.setTaskType("TTS_AUDITION");
            return task;
        });
    }

    @Test
    void auditionPartialFailureSucceedsAndDoesNotMutateStoryboardAudio() throws Exception {
        Character daughter = character(1L, "女儿", "female");
        when(characterMapper.selectList(any())).thenReturn(List.of(daughter));
        byte[] wav = new MockTtsProvider().synthesize("试听", "narrator", 1.0f, 1.0f);
        TtsAuditionAudioGenerator generator = mock(TtsAuditionAudioGenerator.class);
        when(providerFactory.create(7L)).thenReturn(generator);
        when(generator.generate(any()))
                .thenReturn(new TtsAuditionGenerationResult(wav, "prompt-1", "http://comfy/view?filename=1.wav", "inline", 1.2d))
                .thenThrow(new RuntimeException("ComfyUI 节点失败"));
        when(storageService.storeBytes(eq(wav), eq("audios/audition/9/101"), any(), eq("audio/wav"), eq("wav")))
                .thenReturn(new StoredAsset("http://files/audition-1.wav", "local", "audition-1.wav", wav.length, "audio/wav", "audition-1.wav"));

        TtsAuditionRequest request = new TtsAuditionRequest();
        request.setCharacterIds(List.of(1L));
        request.setIncludeNarrator(false);
        request.setCandidateCount(2);
        TaskRecord task = service.startAudition(7L, 9L, request);
        service.generateAuditionAsync(7L, 9L, task.getId(), request);

        assertThat(task.getTaskType()).isEqualTo("TTS_AUDITION");
        verify(storyboardMapper, never()).updateById(any());
        verify(taskRecordMapper).updateById(any(TaskRecord.class));
    }

    private Project project() {
        Project project = new Project();
        project.setId(9L);
        project.setName("测试短剧");
        project.setProjectType("真人短剧");
        project.setGenre("都市情感");
        return project;
    }

    private Character character(Long id, String name, String gender) {
        Character character = new Character();
        character.setId(id);
        character.setProjectId(9L);
        character.setName(name);
        character.setGender(gender);
        character.setDescription("角色设定");
        character.setPersonality("克制");
        return character;
    }
}
```

- [ ] **Step 2: Verify RED**

Run:

```bash
cd backend
mvn -Dtest=TtsAuditionServiceTest#auditionPartialFailureSucceedsAndDoesNotMutateStoryboardAudio test
```

Expected: compile failure because DTOs, factory, and service do not exist.

- [ ] **Step 3: Implement DTOs**

Create `TtsAuditionRoleOverride`:

```java
package com.niren.drama.dto.tts;

import lombok.Data;

import java.util.List;

@Data
public class TtsAuditionRoleOverride {
    private String speakerReferenceAudioUrl;
    private String emotionReferenceAudioUrl;
    private String emotionText;
    private List<Double> emotionVector;
    private Double speed;
}
```

Create `TtsAuditionRequest`:

```java
package com.niren.drama.dto.tts;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TtsAuditionRequest {
    private List<Long> characterIds;
    private Boolean includeNarrator = true;
    private Integer candidateCount;
    private String sampleText;
    private Map<String, TtsAuditionRoleOverride> roleOverrides;
}
```

- [ ] **Step 4: Implement provider factory**

Create `ComfyUiTtsProviderFactory`:

```java
package com.niren.drama.ai.impl;

import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.AiResolvedConfig;
import com.niren.drama.ai.TtsAuditionAudioGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ComfyUiTtsProviderFactory {

    private final AiProviderFactory aiProviderFactory;

    @Value("${niren.ai.tts.workflow-file:tts_indextts2_audition.json}")
    private String defaultWorkflowFile;

    @Value("${niren.ai.tts.audition.max-poll-attempts:900}")
    private int maxPollAttempts;

    @Value("${niren.ai.tts.audition.poll-interval-ms:2000}")
    private long pollIntervalMs;

    @Value("${niren.ai.video.base-url:http://127.0.0.1:8188}")
    private String defaultComfyBaseUrl;

    public TtsAuditionAudioGenerator create(Long userId) {
        AiResolvedConfig tts = aiProviderFactory.resolveConfig(userId, "tts");
        AiResolvedConfig video = aiProviderFactory.resolveConfig(userId, "video");
        String baseUrl = "comfyui".equalsIgnoreCase(tts.provider()) && hasText(tts.baseUrl())
                ? tts.baseUrl()
                : hasText(video.baseUrl()) ? video.baseUrl() : defaultComfyBaseUrl;
        String workflowFile = hasText(tts.model()) && "comfyui".equalsIgnoreCase(tts.provider())
                ? tts.model()
                : defaultWorkflowFile;
        return new ComfyUiTtsProvider(baseUrl, tts.apiKey(), workflowFile, tts.extra(), maxPollAttempts, pollIntervalMs);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
```

- [ ] **Step 5: Implement service minimal behavior**

Create `TtsAuditionService` with constructor dependencies from the test. Include:

- `startAudition(Long userId, Long projectId, TtsAuditionRequest request)`
- `@Async("aiTaskExecutor") generateAuditionAsync(Long userId, Long projectId, Long taskId, TtsAuditionRequest request)`
- role selection from `CharacterMapper`
- sequential candidate generation
- `TaskRecord.result` JSON with `mediaType`, `summary`, and `roles`
- storage under `audios/audition/{projectId}/{taskId}`
- no storyboard updates

Important snippets:

```java
task.setTaskType("TTS_AUDITION");
task.setStatus("PENDING");
task.setProgress(0);
task.setMessage("TTS 角色试听包任务已提交");
taskRecordMapper.insert(task);
selfProvider.getObject().generateAuditionAsync(userId, projectId, task.getId(), request);
```

Candidate storage:

```java
StoredAsset stored = publicAssetStorageService.storeBytes(
        result.audio(),
        "audios/audition/" + projectId + "/" + taskId,
        AudioFormatSupport.filename("role_" + role.characterId() + "_candidate_" + candidateNo, result.audio()),
        AudioFormatSupport.contentTypeFor(result.audio()),
        AudioFormatSupport.extensionFor(result.audio()));
```

Failure rule:

```java
if (generated == 0 && failed > 0) {
    task.setStatus("FAILED");
    task.setMessage("TTS 试听包生成失败，所有候选均不可用");
} else {
    task.setStatus("SUCCESS");
    task.setMessage(failed > 0
            ? String.format("TTS 试听包生成完成：成功%d条，失败%d条", generated, failed)
            : String.format("TTS 试听包生成完成：成功%d条", generated));
}
```

- [ ] **Step 6: Update task type comment**

Modify `TaskRecord` comment:

```java
/** SCRIPT_GEN | STORYBOARD_GEN | IMAGE_GEN | VIDEO_GEN | AUDIO_GEN | VIDEO_COMPOSE | CHARACTER_GEN | TTS_AUDITION */
```

- [ ] **Step 7: Run service test**

Run:

```bash
cd backend
mvn -Dtest=TtsAuditionServiceTest test
```

Expected: test passes.

- [ ] **Step 8: Commit Task 3**

```bash
git add backend/src/main/java/com/niren/drama/dto/tts/TtsAuditionRequest.java \
        backend/src/main/java/com/niren/drama/dto/tts/TtsAuditionRoleOverride.java \
        backend/src/main/java/com/niren/drama/ai/impl/ComfyUiTtsProviderFactory.java \
        backend/src/main/java/com/niren/drama/service/TtsAuditionService.java \
        backend/src/main/java/com/niren/drama/entity/TaskRecord.java \
        backend/src/test/java/com/niren/drama/service/TtsAuditionServiceTest.java
git commit -m "feat: add tts audition service"
```

---

### Task 4: Controller, Configuration, And Verification

**Files:**
- Create: `backend/src/main/java/com/niren/drama/controller/TtsAuditionController.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: extend `backend/src/test/java/com/niren/drama/service/TtsAuditionServiceTest.java`

- [ ] **Step 1: Write failing request normalization test**

Add a service test that requests `candidateCount=20` and verifies only `3` candidates are generated when the configured default max is `3`. If fields are private, use `ReflectionTestUtils.setField(service, "defaultCandidateCount", 3)` and `maxRoles`, `maxTextChars`.

Expected RED: fails because config fields and clamp logic do not exist.

- [ ] **Step 2: Add config fields to service**

In `TtsAuditionService`, add:

```java
@Value("${niren.ai.tts.audition.candidate-count:3}")
private int defaultCandidateCount;

@Value("${niren.ai.tts.audition.max-roles:4}")
private int maxRoles;

@Value("${niren.ai.tts.audition.max-text-chars:80}")
private int maxTextChars;
```

Clamp candidate count:

```java
private int resolveCandidateCount(TtsAuditionRequest request) {
    int requested = request != null && request.getCandidateCount() != null ? request.getCandidateCount() : defaultCandidateCount;
    return Math.max(1, Math.min(3, requested));
}
```

- [ ] **Step 3: Add `application.yml` configuration**

Under `niren.ai.tts` add:

```yaml
      workflow-file: ${AI_TTS_WORKFLOW_FILE:tts_indextts2_audition.json}
      audition:
        candidate-count: ${AI_TTS_AUDITION_CANDIDATE_COUNT:3}
        max-roles: ${AI_TTS_AUDITION_MAX_ROLES:4}
        max-text-chars: ${AI_TTS_AUDITION_MAX_TEXT_CHARS:80}
        poll-interval-ms: ${AI_TTS_AUDITION_POLL_INTERVAL_MS:2000}
        max-poll-attempts: ${AI_TTS_AUDITION_MAX_POLL_ATTEMPTS:900}
```

- [ ] **Step 4: Create controller**

Create:

```java
package com.niren.drama.controller;

import com.niren.drama.common.CurrentUserHelper;
import com.niren.drama.common.Result;
import com.niren.drama.dto.tts.TtsAuditionRequest;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.service.ProjectService;
import com.niren.drama.service.TtsAuditionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "TTS试听评审", description = "生成角色配音试听包，不直接进入视频合成")
@RestController
@RequestMapping("/tts-auditions")
@RequiredArgsConstructor
public class TtsAuditionController {

    private final TtsAuditionService ttsAuditionService;
    private final ProjectService projectService;
    private final CurrentUserHelper currentUserHelper;

    @Operation(summary = "生成项目角色配音试听包")
    @PostMapping("/projects/{projectId}")
    public Result<TaskRecord> create(@PathVariable Long projectId,
                                     @RequestBody(required = false) TtsAuditionRequest request,
                                     @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        projectService.getProject(userId, projectId);
        return Result.success(ttsAuditionService.startAudition(userId, projectId,
                request != null ? request : new TtsAuditionRequest()));
    }
}
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
cd backend
mvn -Dtest=ComfyUiTtsProviderTest,TtsAuditionServiceTest test
```

Expected: all focused tests pass.

- [ ] **Step 6: Run backend test suite**

Run:

```bash
cd backend
mvn test
```

Expected: all backend tests pass.

- [ ] **Step 7: Commit Task 4**

```bash
git add backend/src/main/java/com/niren/drama/controller/TtsAuditionController.java \
        backend/src/main/resources/application.yml \
        backend/src/main/java/com/niren/drama/service/TtsAuditionService.java \
        backend/src/test/java/com/niren/drama/service/TtsAuditionServiceTest.java
git commit -m "feat: expose tts audition api"
```

---

## Final Verification

- [ ] Run backend tests:

```bash
cd backend
mvn test
```

- [ ] Run frontend build only if frontend files were touched:

```bash
cd frontend
npm run build
```

- [ ] Check git status:

```bash
git status --short --branch
```

- [ ] Confirm no generated audio/video artifacts are staged.

## Self-Review

Spec coverage:

- ComfyUI template-driven provider: Tasks 1 and 2.
- Audio output detection and download: Task 2.
- Project-level audition package: Task 3.
- No storyboard audio mutation: Task 3 test.
- Manual-review-compatible result JSON: Task 3.
- Configurable workflow and polling: Task 4.
- API endpoint returning `Result<TaskRecord>`: Task 4.

Placeholder scan: no unresolved placeholders or open-ended test instructions should remain.

Type consistency: DTO names use `TtsAudition*`; provider boundary uses `TtsAuditionGeneration*`; controller path is `/tts-auditions/projects/{projectId}`.
