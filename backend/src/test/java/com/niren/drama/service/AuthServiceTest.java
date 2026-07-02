package com.niren.drama.service;

import com.niren.drama.dto.auth.LoginRequest;
import com.niren.drama.dto.auth.LoginResponse;
import com.niren.drama.entity.User;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.UserMapper;
import com.niren.drama.security.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final CaptchaService captchaService = mock(CaptchaService.class);
    private final AuthService service = new AuthService(
            userMapper,
            passwordEncoder,
            authenticationManager,
            jwtUtils,
            captchaService);

    @Test
    void loginRejectsInvalidCaptchaBeforeAuthenticatingPassword() {
        LoginRequest request = loginRequest();
        when(captchaService.validateCaptcha("captcha-1", "PASSIVE:82:1200:6")).thenReturn(false);

        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("安全验证失败或已过期");
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void loginAuthenticatesAfterValidCaptcha() {
        LoginRequest request = loginRequest();
        Authentication authentication = mock(Authentication.class);
        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername("alice")
                .password("encoded")
                .roles("USER")
                .build();
        User user = new User();
        user.setId(88L);
        user.setUsername("alice");
        user.setNickname("Alice");
        user.setAvatar("https://img.example/alice.png");
        user.setRoles("USER");

        when(captchaService.validateCaptcha("captcha-1", "PASSIVE:82:1200:6")).thenReturn(true);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(jwtUtils.generateToken("alice", 88L)).thenReturn("jwt-token");

        LoginResponse response = service.login(request);

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getUsername()).isEqualTo("alice");
        assertThat(response.getUserId()).isEqualTo(88L);
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    private LoginRequest loginRequest() {
        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("secret123");
        request.setCaptchaId("captcha-1");
        request.setCaptchaCode("PASSIVE:82:1200:6");
        return request;
    }
}
