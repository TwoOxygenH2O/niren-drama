package com.niren.drama.controller;

import com.niren.drama.common.Result;
import com.niren.drama.dto.auth.CaptchaResponse;
import com.niren.drama.dto.auth.LoginRequest;
import com.niren.drama.dto.auth.LoginResponse;
import com.niren.drama.dto.auth.RegisterRequest;
import com.niren.drama.entity.User;
import com.niren.drama.service.AuthService;
import com.niren.drama.service.CaptchaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证接口", description = "用户登录、注册、验证码、用户信息")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;

    @Operation(summary = "获取登录验证码")
    @GetMapping("/captcha")
    public Result<CaptchaResponse> captcha() {
        return Result.success(captchaService.generateCaptcha());
    }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<Void> register(@RequestBody @Valid RegisterRequest request) {
        authService.register(request);
        return Result.success();
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<User> me(@AuthenticationPrincipal UserDetails userDetails) {
        User user = authService.getUserByUsername(userDetails.getUsername());
        user.setPassword(null);
        return Result.success(user);
    }
}
