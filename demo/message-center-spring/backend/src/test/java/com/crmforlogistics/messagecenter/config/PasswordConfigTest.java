package com.crmforlogistics.messagecenter.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordConfigTest {
    @Test
    void passwordEncoderIsAvailableWithoutTheWebSecurityConfiguration() {
        try (var context = new AnnotationConfigApplicationContext(PasswordConfig.class)) {
            PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
            String hash = encoder.encode("secret");

            assertThat(encoder.matches("secret", hash)).isTrue();
        }
    }
}
