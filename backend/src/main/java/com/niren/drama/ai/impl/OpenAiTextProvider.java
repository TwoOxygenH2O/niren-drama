package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.AiOutputTruncatedException;
import com.niren.drama.ai.ChatMessage;
import com.niren.drama.ai.TextAiProvider;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
public class OpenAiTextProvider implements TextAiProvider {

    private static final int OPENAI_COMPATIBLE_MAX_TOKENS = 8192;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Integer maxTokens;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiTextProvider(String baseUrl, String apiKey, String model, Integer maxTokens) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        return chatWithHistory(systemPrompt, List.of(new ChatMessage("user", userMessage)));
    }

    @Override
    public String chatWithHistory(String systemPrompt, List<ChatMessage> messages) {
        try {
            ObjectNode body = buildRequestBody(systemPrompt, messages, false);

            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(buildChatCompletionsUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.error("智能文本接口异常: 状态码={}, 响应体={}", response.statusCode(), response.body());
                throw new RuntimeException("AI text service error: HTTP " + response.statusCode());
            }
            JsonNode responseJson = objectMapper.readTree(response.body());
            String content = responseJson.path("choices").path(0).path("message").path("content").asText("");
            String finishReason = responseJson.path("choices").path(0).path("finish_reason").asText("");
            if ("length".equalsIgnoreCase(finishReason)) {
                throw new AiOutputTruncatedException("AI输出达到长度上限，请缩短输入或提高 max_tokens 后重试", content);
            }
            return content;
        } catch (AiOutputTruncatedException e) {
            throw e;
        } catch (Exception e) {
            log.error("文本接口调用失败", e);
            throw new RuntimeException("AI text service unavailable: " + e.getMessage());
        }
    }

    @Override
    public void streamChat(String systemPrompt, String userMessage, Consumer<String> chunkConsumer) {
        streamChatWithHistory(systemPrompt, List.of(new ChatMessage("user", userMessage)), chunkConsumer);
    }

    @Override
    public void streamChatWithHistory(String systemPrompt, List<ChatMessage> messages, Consumer<String> chunkConsumer) {
        try {
            ObjectNode body = buildRequestBody(systemPrompt, messages, true);
            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(buildChatCompletionsUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofMinutes(10))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException("AI text service error: HTTP " + response.statusCode() + " - " + errorBody);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                boolean truncated = false;
                StringBuilder partialContent = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode jsonNode = objectMapper.readTree(data);
                    String finishReason = jsonNode.path("choices").path(0).path("finish_reason").asText("");
                    if ("length".equalsIgnoreCase(finishReason)) {
                        truncated = true;
                    }
                    JsonNode delta = jsonNode.path("choices").path(0).path("delta").path("content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        String chunk = delta.asText();
                        partialContent.append(chunk);
                        chunkConsumer.accept(chunk);
                    }
                }
                if (truncated) {
                    throw new AiOutputTruncatedException("AI输出达到长度上限，请缩短剧本内容或提高 max_tokens 后重试", partialContent.toString());
                }
            }
        } catch (AiOutputTruncatedException e) {
            throw e;
        } catch (Exception e) {
            log.error("文本流式接口调用失败", e);
            throw new RuntimeException("AI text service unavailable: " + e.getMessage());
        }
    }

    /**
     * Build the chat completions URL, handling different provider URL formats.
     * DeepSeek: https://api.deepseek.com/chat/completions (no /v1 prefix)
     * OpenAI: https://api.openai.com/v1/chat/completions
     * DashScope: https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
     */
    private String buildChatCompletionsUrl() {
        String normalizedUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // If the base URL already ends with a path segment that suggests it's the completions endpoint
        if (normalizedUrl.endsWith("/chat/completions")) {
            return normalizedUrl;
        }
        return normalizedUrl + "/chat/completions";
    }

    private ObjectNode buildRequestBody(String systemPrompt, List<ChatMessage> messages, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode msgs = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sysMsg = msgs.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
        }
        for (ChatMessage msg : messages) {
            ObjectNode m = msgs.addObject();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
        }
        body.put("temperature", 0.7);
        Integer effectiveMaxTokens = sanitizeMaxTokens(maxTokens);
        if (effectiveMaxTokens != null) {
            body.put("max_tokens", effectiveMaxTokens);
        }
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    private Integer sanitizeMaxTokens(Integer configuredMaxTokens) {
        if (configuredMaxTokens == null || configuredMaxTokens <= 0) {
            return null;
        }
        if (configuredMaxTokens > OPENAI_COMPATIBLE_MAX_TOKENS) {
            log.warn("配置的 max_tokens={} 超过兼容上限 {}，已自动收敛",
                    configuredMaxTokens,
                    OPENAI_COMPATIBLE_MAX_TOKENS);
            return OPENAI_COMPATIBLE_MAX_TOKENS;
        }
        return configuredMaxTokens;
    }
}
