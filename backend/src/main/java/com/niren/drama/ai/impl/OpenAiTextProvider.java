package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.ChatMessage;
import com.niren.drama.ai.TextAiProvider;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Slf4j
public class OpenAiTextProvider implements TextAiProvider {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiTextProvider(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
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
            body.put("max_tokens", 4096);

            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode responseJson = objectMapper.readTree(response.body());
            return responseJson.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.error("OpenAI text API call failed", e);
            throw new RuntimeException("AI text service unavailable: " + e.getMessage());
        }
    }
}
