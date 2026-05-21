package com.niren.drama.common.sse;

import java.util.function.Consumer;

/**
 * 将模型返回的较大文本块拆成更小的 Unicode 码点片，用于 SSE 逐片下发，使前端呈现接近打字机效果。
 */
public final class SseTextChunkFanout {

    private SseTextChunkFanout() {
    }

    /**
     * @param text                待拆分文本
     * @param maxCodePointsPerSlice 每片最多包含的码点数（至少为 1）
     * @param sink                每一片的下发回调
     */
    public static void forEachCodePointSlice(String text, int maxCodePointsPerSlice, Consumer<String> sink) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int max = Math.max(1, maxCodePointsPerSlice);
        int i = 0;
        while (i < text.length()) {
            int end = i;
            int count = 0;
            while (end < text.length() && count < max) {
                int cp = text.codePointAt(end);
                end += Character.charCount(cp);
                count++;
            }
            sink.accept(text.substring(i, end));
            i = end;
        }
    }
}
