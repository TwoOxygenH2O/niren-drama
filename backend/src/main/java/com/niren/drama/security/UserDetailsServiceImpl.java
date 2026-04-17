package com.niren.drama.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.entity.User;
import com.niren.drama.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .eq(User::getDeleted, 0));
        if (user == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }
        List<SimpleGrantedAuthority> authorities = Arrays.stream(
                user.getRoles() != null ? user.getRoles().split(",") : new String[]{"USER"})
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.trim()))
                .collect(Collectors.toList());
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .authorities(authorities)
                .disabled(user.getStatus() != null && user.getStatus() == 0)
                .build();
    }
}
