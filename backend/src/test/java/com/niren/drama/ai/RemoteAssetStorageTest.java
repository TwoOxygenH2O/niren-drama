package com.niren.drama.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteAssetStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void strictMp4PersistenceRejectsInvalidVideoAndCleansLocalFile() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        try {
            server.createContext("/bad.mp4", exchange -> write(exchange, 200, "video/mp4",
                    "not a playable mp4".getBytes(StandardCharsets.UTF_8)));
            server.start();

            String sourceUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/bad.mp4";
            assertThatThrownBy(() -> RemoteAssetStorage.persistHttpUrlStrict(
                    sourceUrl,
                    tempDir.toString(),
                    "http://localhost:8080/api/files",
                    "generated-videos",
                    HttpClient.newHttpClient(),
                    "mp4"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("远程资源落盘失败");

            Path outputDir = tempDir.resolve("generated-videos");
            long fileCount = 0;
            if (Files.exists(outputDir)) {
                try (Stream<Path> files = Files.list(outputDir)) {
                    fileCount = files.count();
                }
            }
            assertThat(fileCount).isZero();
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
