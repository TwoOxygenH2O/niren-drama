package com.niren.drama.ai;

import java.util.List;
import java.util.function.Consumer;

public interface TextAiProvider {

    /**
     * Single-turn chat with a system prompt.
     */
    String chat(String systemPrompt, String userMessage);

    /**
     * Multi-turn chat with message history.
     */
    String chatWithHistory(String systemPrompt, List<ChatMessage> messages);

    default void streamChat(String systemPrompt, String userMessage, Consumer<String> chunkConsumer) {
        streamChatWithHistory(systemPrompt, List.of(new ChatMessage("user", userMessage)), chunkConsumer);
    }

    default void streamChatWithHistory(String systemPrompt, List<ChatMessage> messages, Consumer<String> chunkConsumer) {
        chunkConsumer.accept(chatWithHistory(systemPrompt, messages));
    }
}
