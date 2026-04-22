package com.niren.drama.common;

import lombok.Data;
import java.io.Serializable;

@Data
public class Result<T> implements Serializable {

    private int code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.setCode(200);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> fail(String message) {
        return fail(500, message, null);
    }

    public static <T> Result<T> fail(String message, T data) {
        return fail(500, message, data);
    }

    public static <T> Result<T> fail(int code, String message) {
        return fail(code, message, null);
    }

    public static <T> Result<T> fail(int code, String message, T data) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        r.setData(data);
        return r;
    }
}
