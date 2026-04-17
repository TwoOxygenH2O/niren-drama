package com.niren.drama.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("倪人短剧 AI 自动化生产平台 API")
                        .description("全栈 AI 短剧自动化生产平台，实现「一句话创意→剧本生成→分镜拆解→AI生图/生视频→配音字幕→自动合成成片」全流程")
                        .version("1.0.0")
                        .contact(new Contact().name("Niren Drama").url("https://github.com/TwoOxygenH2O/niren-drama")))
                .addSecurityItem(new SecurityRequirement().addList("JWT"))
                .components(new Components()
                        .addSecuritySchemes("JWT", new SecurityScheme()
                                .name("Authorization")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
