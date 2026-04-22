package com.niren.drama.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;
    private final Object data;

    public BusinessException(String message) {
        this(500, message, null);
    }

    public BusinessException(String message, Object data) {
        this(500, message, data);
    }

    public BusinessException(int code, String message) {
        this(code, message, null);
    }

    public BusinessException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }
}
