package com.niren.drama.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CaptchaServiceSpringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("niren.auth.captcha.ttl-seconds=45")
            .withBean(CaptchaService.class);

    @Test
    void createsCaptchaServiceWithConfiguredTtl() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CaptchaService.class);
            CaptchaService service = context.getBean(CaptchaService.class);
            assertThat(service.generateCaptcha().getExpiresIn()).isEqualTo(45);
        });
    }
}
