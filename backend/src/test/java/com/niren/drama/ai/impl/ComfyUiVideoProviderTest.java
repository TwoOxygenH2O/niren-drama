package com.niren.drama.ai.impl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComfyUiVideoProviderTest {

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
