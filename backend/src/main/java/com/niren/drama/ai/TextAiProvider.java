package com.niren.drama.ai;

import java.util.List;

public interface TextAiProvider {

    /**
     * Single-turn chat with a system prompt.
     */
    String chat(String systemPrompt, String userMessage);

    /**
     * Multi-turn chat with message history.
     */
    String chatWithHistory(String systemPrompt, List<ChatMessage> messages);
}
