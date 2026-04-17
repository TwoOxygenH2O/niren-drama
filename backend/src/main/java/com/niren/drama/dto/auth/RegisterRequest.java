package com.niren.drama.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 30, message = "用户名长度3-30位")
    private String username;
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 30, message = "密码长度6-30位")
    private String password;
    private String nickname;
    private String email;
}
