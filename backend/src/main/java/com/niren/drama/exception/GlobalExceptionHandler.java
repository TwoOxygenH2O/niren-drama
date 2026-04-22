package com.niren.drama.exception;

import com.niren.drama.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import jakarta.servlet.http.HttpServletRequest;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Object> handleBusinessException(BusinessException e, HttpServletRequest request) {
    Object errorData = e.getData();
        String message = sanitizeErrorMessage(e.getMessage(), request.getRequestURI());
    log.warn("Business exception: code={}, message={}, dataType={}",
        e.getCode(),
                message,
        errorData == null ? "null" : errorData.getClass().getName());

    Result<Object> result = new Result<>();
    result.setCode(e.getCode());
        result.setMessage(message);
    result.setData(errorData);
    return result;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst().orElse("Validation failed");
        return Result.fail(400, message);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst().orElse("Bind failed");
        return Result.fail(400, message);
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleBadCredentials(BadCredentialsException e) {
        return Result.fail(401, "用户名或密码错误");
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        return Result.fail(403, "无权限访问");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return Result.fail(413, "文件大小超出限制");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("Unexpected exception", e);
        String message = sanitizeErrorMessage(e.getMessage(), request.getRequestURI());
        if (!message.equals(e.getMessage())) {
            return Result.fail(message);
        }
        return Result.fail("系统内部错误: " + e.getMessage());
    }

    private String sanitizeErrorMessage(String message, String requestUri) {
        if (message == null || message.isBlank()) {
            return "请求处理失败";
        }
        if (!isInternalStringTypeMismatch(message)) {
            return message;
        }
        if (requestUri == null) {
            return "系统返回了格式异常的数据，请刷新页面后重试";
        }
        if (requestUri.contains("/scripts/preview/outline")) {
            return "大纲预览保存失败：内容格式异常，请保留项目通用信息和每集大纲标记后重试";
        }
        if (requestUri.contains("/scripts/preview/batch")) {
            return "批量剧本预览保存失败：内容格式异常，请保留每集开始和结束标记后重试";
        }
        if (requestUri.endsWith("/scripts") || requestUri.matches(".*/scripts/\\d+$")) {
            return "剧本保存失败：标题、大纲和正文必须是纯文本，请检查后重试";
        }
        if (requestUri.contains("/storyboards")) {
            return "分镜预览保存失败：内容格式异常，请检查 JSON 结构和字段后重试";
        }
        return "系统返回了格式异常的数据，请刷新页面后重试";
    }

    private boolean isInternalStringTypeMismatch(String message) {
        return message.contains("!= java.lang.String")
                || message.contains(" cannot be cast to class java.lang.String")
                || message.contains("Cannot deserialize value of type `java.lang.String`")
                || message.contains("Cannot deserialize value of type java.lang.String");
    }
}
