package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.dto.auth.LoginRequest;
import com.niren.drama.dto.auth.LoginResponse;
import com.niren.drama.dto.auth.RegisterRequest;
import com.niren.drama.entity.User;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.UserMapper;
import com.niren.drama.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final CaptchaService captchaService;

    public LoginResponse login(LoginRequest request) {
        if (!captchaService.validateCaptcha(request.getCaptchaId(), request.getCaptchaCode())) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        UserDetails userDetails = (UserDetails) auth.getPrincipal();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, userDetails.getUsername()));
        String token = jwtUtils.generateToken(user.getUsername(), user.getId());
        return new LoginResponse(token, user.getUsername(),
                user.getNickname(), user.getAvatar(), user.getId(), user.getRoles());
    }

    public void register(RegisterRequest request) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());
        user.setEmail(request.getEmail());
        user.setStatus(1);
        user.setRoles("USER");
        userMapper.insert(user);
    }

    public User getUserByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
    }
}
