package com.niren.drama.ai;

public class AiOutputTruncatedException extends RuntimeException {

    private final String partialContent;

    public AiOutputTruncatedException(String message, String partialContent) {
        super(message);
        this.partialContent = partialContent;
    }

    public String getPartialContent() {
        return partialContent;
    }
}