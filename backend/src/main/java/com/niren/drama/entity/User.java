package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class User extends BaseEntity {
    private String username;
    private String password;
    private String nickname;
    private String email;
    private String avatar;
    /** 0-disabled, 1-active */
    private Integer status;
    /** comma-separated roles: ADMIN,USER */
    private String roles;
}
