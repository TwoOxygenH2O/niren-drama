package com.niren.drama.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.entity.User;
import com.niren.drama.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CurrentUserHelper {

    private final UserMapper userMapper;

    public Long getUserId(UserDetails userDetails) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, userDetails.getUsername()));
        if (user == null) {
            throw new RuntimeException("User not found");
        }
        return user.getId();
    }
}
