package com.niren.drama.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_ai_config")
public class AiConfig extends BaseEntity {
    private Long userId;
    /** text | image | video | tts */
    private String configType;
    /** openai | doubao | qianwen | wenxin | kling | runway | volcengine | minimax */
    private String provider;
    private String baseUrl;
    private String apiKey;
    private String model;
    /** JSON extra config */
    private String extra;
    private Integer isDefault;
}
