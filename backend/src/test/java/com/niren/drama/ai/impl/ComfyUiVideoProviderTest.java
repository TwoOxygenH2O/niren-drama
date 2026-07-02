package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComfyUiVideoProviderTest {

    @Test
    void injectPromptIntoWorkflowPrefersExistingPositiveClipTextOverEmptyNegativeClipText() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode workflow = mapper.createObjectNode();
        ObjectNode negative = workflow.putObject("93");
        negative.put("class_type", "CLIPTextEncode");
        negative.putObject("inputs").put("text", "");
        ObjectNode positive = workflow.putObject("44");
        positive.put("class_type", "CLIPTextEncode");
        positive.putObject("inputs").put("text", "old positive prompt");

        ComfyUiVideoProvider provider = new ComfyUiVideoProvider("http://127.0.0.1:8188", "", "", "", "", "");
        Method method = ComfyUiVideoProvider.class.getDeclaredMethod("injectPromptIntoWorkflow", ObjectNode.class, String.class);
        method.setAccessible(true);
        method.invoke(provider, workflow, "new heroine revenge motion prompt");

        assertThat(workflow.path("93").path("inputs").path("text").asText()).isEmpty();
        assertThat(workflow.path("44").path("inputs").path("text").asText())
                .isEqualTo("new heroine revenge motion prompt");
    }

    @Test
    void injectVideoParamsSetsHunyuanLengthAndSingleBatch() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode workflow = mapper.createObjectNode();
        ObjectNode hunyuan = workflow.putObject("78");
        hunyuan.put("class_type", "HunyuanVideo15ImageToVideo");
        ObjectNode inputs = hunyuan.putObject("inputs");
        inputs.put("width", 1280);
        inputs.put("height", 720);
        inputs.put("length", 1);
        inputs.put("batch_size", 121);

        ComfyUiVideoProvider provider = new ComfyUiVideoProvider("http://127.0.0.1:8188", "", "", "", "", "");
        Method method = ComfyUiVideoProvider.class.getDeclaredMethod(
                "injectVideoParams",
                ObjectNode.class,
                int.class,
                int.class,
                int.class);
        method.setAccessible(true);
        method.invoke(provider, workflow, 720, 1280, 49);

        assertThat(inputs.path("width").asInt()).isEqualTo(720);
        assertThat(inputs.path("height").asInt()).isEqualTo(1280);
        assertThat(inputs.path("length").asInt()).isEqualTo(49);
        assertThat(inputs.path("batch_size").asInt()).isEqualTo(1);
    }

    @Test
    void generateVideoFromImageUploadsRemoteImageBeforeSubmittingPrompt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        AtomicBoolean imageUploaded = new AtomicBoolean(false);
        AtomicReference<String> promptBody = new AtomicReference<>("");
        try {
            server.createContext("/source.png", exchange -> write(exchange, 200, "image/png", new byte[]{1, 2, 3, 4}));
            server.createContext("/upload/image", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                imageUploaded.set("POST".equals(exchange.getRequestMethod()) && body.length > 0);
                write(exchange, 200, "application/json", "{\"name\":\"uploaded.png\",\"subfolder\":\"\",\"type\":\"input\"}".getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/prompt", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                promptBody.set(body);
                if (!imageUploaded.get() || body.contains("/source.png")) {
                    write(exchange, 400, "application/json", "{\"error\":\"LoadImage received remote URL\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                write(exchange, 200, "application/json", "{\"prompt_id\":\"p1\"}".getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/history/p1", exchange -> write(exchange, 200, "application/json", "{\"p1\":{\"outputs\":{\"6\":{\"videos\":[{\"filename\":\"out.mp4\"}]}},\"status\":{\"status_str\":\"success\"}}}".getBytes(StandardCharsets.UTF_8)));
            server.start();

            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            String extra = "{\"workflow\":{\"3\":{\"class_type\":\"LoadImage\",\"inputs\":{\"image\":\"old.png\"}},\"6\":{\"class_type\":\"VHS_VideoCombine\",\"inputs\":{}}}}";
            ComfyUiVideoProvider provider = new ComfyUiVideoProvider(baseUrl, "", "", extra, "", "");

            String result = provider.generateVideoFromImage(baseUrl + "/source.png", "镜头推进", 5, "720x1280", "standard", false);

            assertThat(result).isEqualTo(baseUrl + "/view?filename=out.mp4&type=output");
            assertThat(promptBody.get()).contains("uploaded.png");
            assertThat(promptBody.get()).doesNotContain("/source.png");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateVideoFromImageUsesLtx23Fp8WorkflowWhenConfigured() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        AtomicReference<String> promptBody = new AtomicReference<>("");
        try {
            server.createContext("/source.png", exchange -> write(exchange, 200, "image/png", new byte[]{1, 2, 3, 4}));
            server.createContext("/upload/image", exchange -> write(exchange, 200, "application/json", "{\"name\":\"uploaded.png\",\"subfolder\":\"\",\"type\":\"input\"}".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/prompt", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                promptBody.set(body);
                write(exchange, 200, "application/json", "{\"prompt_id\":\"p2\"}".getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/history/p2", exchange -> write(exchange, 200, "application/json", "{\"p2\":{\"outputs\":{\"15\":{\"videos\":[{\"filename\":\"out.mp4\"}]}},\"status\":{\"status_str\":\"success\"}}}".getBytes(StandardCharsets.UTF_8)));
            server.start();

            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ComfyUiVideoProvider provider = new ComfyUiVideoProvider(
                    baseUrl,
                    "",
                    "ltx-2.3-22b-dev-fp8.safetensors",
                    "",
                    "",
                    "");

            String result = provider.generateVideoFromImage(baseUrl + "/source.png", "slow push-in", 5, "720x1280", "pro", false);

            assertThat(result).isEqualTo(baseUrl + "/view?filename=out.mp4&type=output");
            assertThat(promptBody.get()).contains("ltx-2.3-22b-dev-fp8.safetensors");
            assertThat(promptBody.get()).contains("uploaded.png");
            assertThat(promptBody.get()).contains("\"save_output\":true");
            assertThat(promptBody.get()).contains("LTXVImgToVideoConditionOnly");
            assertThat(promptBody.get()).contains("Motion engine: LTX 2.3 Pro FP8 image-to-video.");
            assertThat(promptBody.get()).doesNotContain("WAN 2.2 image-to-video");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateVideoFromImageUsesStableWanLightx2vWorkflowByDefault() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        AtomicReference<String> promptBody = new AtomicReference<>("");
        try {
            server.createContext("/source.png", exchange -> write(exchange, 200, "image/png", new byte[]{1, 2, 3, 4}));
            server.createContext("/upload/image", exchange -> write(exchange, 200, "application/json", "{\"name\":\"uploaded.png\",\"subfolder\":\"\",\"type\":\"input\"}".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/prompt", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                promptBody.set(body);
                write(exchange, 200, "application/json", "{\"prompt_id\":\"p3\"}".getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/history/p3", exchange -> write(exchange, 200, "application/json", "{\"p3\":{\"outputs\":{\"24\":{\"videos\":[{\"filename\":\"wan.mp4\"}]}},\"status\":{\"status_str\":\"success\"}}}".getBytes(StandardCharsets.UTF_8)));
            server.start();

            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ComfyUiVideoProvider provider = new ComfyUiVideoProvider(
                    baseUrl,
                    "",
                    "wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors",
                    "",
                    "",
                    "");

            String result = provider.generateVideoFromImage(baseUrl + "/source.png", "Motion intensity: medium, heroine turns and walks through palace gate", 7, "720x1280", "pro", false);

            assertThat(result).isEqualTo(baseUrl + "/view?filename=wan.mp4&type=output");
            assertThat(promptBody.get()).contains("niren_wan22_i2v_short_drama");
            assertThat(promptBody.get()).contains("wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors");
            assertThat(promptBody.get()).contains("wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors");
            assertThat(promptBody.get()).contains("wan2.2_i2v_lightx2v_4steps_lora_v1_high_noise.safetensors");
            assertThat(promptBody.get()).contains("wan2.2_i2v_lightx2v_4steps_lora_v1_low_noise.safetensors");
            assertThat(promptBody.get()).contains("\"steps\":4");
            assertThat(promptBody.get()).contains("\"save_metadata\":false");
            assertThat(promptBody.get()).contains("Motion intensity: medium");
            assertThat(promptBody.get()).doesNotContain("Motion intensity: low");
            assertThat(promptBody.get()).contains("\"riflex_freq_index\":0");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateVideoFromImageDoesNotDowngradeActionShotToLowMotionBecauseOfGenericBreathingPrompt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        AtomicReference<String> promptBody = new AtomicReference<>("");
        try {
            server.createContext("/source.png", exchange -> write(exchange, 200, "image/png", new byte[]{1, 2, 3, 4}));
            server.createContext("/upload/image", exchange -> write(exchange, 200, "application/json", "{\"name\":\"uploaded.png\",\"subfolder\":\"\",\"type\":\"input\"}".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/prompt", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                promptBody.set(body);
                write(exchange, 200, "application/json", "{\"prompt_id\":\"p33\"}".getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/history/p33", exchange -> write(exchange, 200, "application/json", "{\"p33\":{\"outputs\":{\"24\":{\"videos\":[{\"filename\":\"wan.mp4\"}]}},\"status\":{\"status_str\":\"success\"}}}".getBytes(StandardCharsets.UTF_8)));
            server.start();

            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ComfyUiVideoProvider provider = new ComfyUiVideoProvider(
                    baseUrl,
                    "",
                    "wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors",
                    "",
                    "",
                    "");

            provider.generateVideoFromImage(baseUrl + "/source.png",
                    List.of(),
                    "heroine opens her eyes, candle flickers, hand grips decree, cloth folds move",
                    7,
                    "720x1280",
                    "pro",
                    false);

            assertThat(promptBody.get()).contains("natural breathing");
            assertThat(promptBody.get()).contains("eye blink");
            assertThat(promptBody.get()).contains("locked-off or almost locked camera");
            assertThat(promptBody.get()).contains("no whole-frame pan");
            assertThat(promptBody.get()).contains("no gif-like zoom");
            assertThat(promptBody.get()).doesNotContain("gentle pan, or slight handheld parallax");
            assertThat(promptBody.get()).contains("Motion intensity: medium");
            assertThat(promptBody.get()).doesNotContain("Motion intensity: low");
            assertThat(promptBody.get()).contains("\"noise_aug_strength\":0.038");
            assertThat(promptBody.get()).contains("\"start_latent_strength\":0.94");
            assertThat(promptBody.get()).contains("\"steps\":4");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateVideoFromImageUsesWanSeriesBalancedWorkflowWithFrameCap() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        AtomicReference<String> promptBody = new AtomicReference<>("");
        try {
            server.createContext("/source.png", exchange -> write(exchange, 200, "image/png", new byte[]{1, 2, 3, 4}));
            server.createContext("/upload/image", exchange -> write(exchange, 200, "application/json", "{\"name\":\"uploaded.png\",\"subfolder\":\"\",\"type\":\"input\"}".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/prompt", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                promptBody.set(body);
                write(exchange, 200, "application/json", "{\"prompt_id\":\"p4\"}".getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/history/p4", exchange -> write(exchange, 200, "application/json", "{\"p4\":{\"outputs\":{\"24\":{\"videos\":[{\"filename\":\"series.mp4\"}]}},\"status\":{\"status_str\":\"success\"}}}".getBytes(StandardCharsets.UTF_8)));
            server.start();

            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            String extra = "{\"workflowFile\":\"video_wan2_2_14B_i2v_series_balanced.json\",\"qualityMode\":\"wan22-series-balanced\",\"maxFrames\":33,\"maxSteps\":12,\"frameRate\":8}";
            ComfyUiVideoProvider provider = new ComfyUiVideoProvider(
                    baseUrl,
                    "",
                    "wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors",
                    extra,
                    "",
                    "");

            String result = provider.generateVideoFromImage(baseUrl + "/source.png", "Motion intensity: high, heroine turns and raises sleeve", 8, "720x1280", "pro", false);

            assertThat(result).isEqualTo(baseUrl + "/view?filename=series.mp4&type=output");
            assertThat(promptBody.get()).contains("niren_wan22_i2v_series_balanced");
            assertThat(promptBody.get()).contains("\"num_frames\":65");
            assertThat(promptBody.get()).contains("\"steps\":12");
            assertThat(promptBody.get()).contains("\"frame_rate\":16");
            assertThat(promptBody.get()).doesNotContain("\"steps\":30");
            assertThat(promptBody.get()).doesNotContain("\"riflex_freq_index\":6");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateVideoFromImageIgnoresStaleLowWanFrameRateOverride() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        AtomicReference<String> promptBody = new AtomicReference<>("");
        try {
            server.createContext("/source.png", exchange -> write(exchange, 200, "image/png", new byte[]{1, 2, 3, 4}));
            server.createContext("/upload/image", exchange -> write(exchange, 200, "application/json", "{\"name\":\"uploaded.png\",\"subfolder\":\"\",\"type\":\"input\"}".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/prompt", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                promptBody.set(body);
                write(exchange, 200, "application/json", "{\"prompt_id\":\"p44\"}".getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/history/p44", exchange -> write(exchange, 200, "application/json", "{\"p44\":{\"outputs\":{\"24\":{\"videos\":[{\"filename\":\"series.mp4\"}]}},\"status\":{\"status_str\":\"success\"}}}".getBytes(StandardCharsets.UTF_8)));
            server.start();

            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            String extra = "{\"workflowFile\":\"video_wan2_2_14B_i2v_series_balanced.json\",\"qualityMode\":\"wan22-series-balanced\",\"maxFrames\":33,\"maxSteps\":12,\"frameRate\":4}";
            ComfyUiVideoProvider provider = new ComfyUiVideoProvider(
                    baseUrl,
                    "",
                    "wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors",
                    extra,
                    "",
                    "");

            provider.generateVideoFromImage(baseUrl + "/source.png", "heroine turns with readable cloth motion", 8, "720x1280", "pro", false);

            assertThat(promptBody.get()).contains("\"num_frames\":65");
            assertThat(promptBody.get()).contains("\"frame_rate\":16");
            assertThat(promptBody.get()).doesNotContain("\"frame_rate\":4");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateVideoFromImageUsesHunyuan15WorkflowWithLengthCap() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        AtomicReference<String> promptBody = new AtomicReference<>("");
        try {
            server.createContext("/source.png", exchange -> write(exchange, 200, "image/png", new byte[]{1, 2, 3, 4}));
            server.createContext("/upload/image", exchange -> write(exchange, 200, "application/json", "{\"name\":\"uploaded.png\",\"subfolder\":\"\",\"type\":\"input\"}".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/prompt", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                promptBody.set(body);
                write(exchange, 200, "application/json", "{\"prompt_id\":\"p5\"}".getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/history/p5", exchange -> write(exchange, 200, "application/json", "{\"p5\":{\"outputs\":{\"16\":{\"videos\":[{\"filename\":\"hunyuan.mp4\"}]}},\"status\":{\"status_str\":\"success\"}}}".getBytes(StandardCharsets.UTF_8)));
            server.start();

            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            String extra = "{\"workflowFile\":\"video_hunyuan_video_1.5_720p_i2v.json\",\"qualityMode\":\"hunyuan15-i2v-720p\",\"maxFrames\":49,\"maxSteps\":12}";
            ComfyUiVideoProvider provider = new ComfyUiVideoProvider(
                    baseUrl,
                    "",
                    "hunyuanvideo1.5_720p_i2v_fp16.safetensors",
                    extra,
                    "",
                    "");

            String result = provider.generateVideoFromImage(baseUrl + "/source.png", "heroine opens her eyes, candle flickers", 8, "720x1280", "pro", false);

            assertThat(result).isEqualTo(baseUrl + "/view?filename=hunyuan.mp4&type=output");
            assertThat(promptBody.get()).contains("HunyuanVideo15ImageToVideo");
            assertThat(promptBody.get()).contains("hunyuanvideo1.5_720p_i2v_fp16.safetensors");
            assertThat(promptBody.get()).contains("\"length\":49");
            assertThat(promptBody.get()).contains("\"batch_size\":1");
            assertThat(promptBody.get()).contains("heroine opens her eyes, candle flickers");
            assertThat(promptBody.get()).contains("slideshow");
            assertThat(promptBody.get()).doesNotContain("WanVideoModelLoader");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateVideoFromImageReportsRunningQueueWhenPollingTimesOut() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        try {
            server.createContext("/source.png", exchange -> write(exchange, 200, "image/png", new byte[]{1, 2, 3, 4}));
            server.createContext("/upload/image", exchange -> write(exchange, 200, "application/json", "{\"name\":\"uploaded.png\",\"subfolder\":\"\",\"type\":\"input\"}".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/prompt", exchange -> write(exchange, 200, "application/json", "{\"prompt_id\":\"p1\"}".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/history/p1", exchange -> write(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/queue", exchange -> write(exchange, 200, "application/json", "{\"queue_running\":[[10,\"p1\",{}]],\"queue_pending\":[]}".getBytes(StandardCharsets.UTF_8)));
            server.start();

            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            String extra = "{\"workflow\":{\"3\":{\"class_type\":\"LoadImage\",\"inputs\":{\"image\":\"old.png\"}},\"6\":{\"class_type\":\"VHS_VideoCombine\",\"inputs\":{}}}}";
            ComfyUiVideoProvider provider = new ComfyUiVideoProvider(baseUrl, "", "", extra, "", "", 1, 1);

            assertThatThrownBy(() -> provider.generateVideoFromImage(baseUrl + "/source.png", "镜头推进", 5, "720x1280", "standard", false))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("ComfyUI 视频任务仍在执行中")
                    .hasMessageContaining("prompt_id=p1");
        } finally {
            server.stop(0);
        }
    }

    private static void write(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
