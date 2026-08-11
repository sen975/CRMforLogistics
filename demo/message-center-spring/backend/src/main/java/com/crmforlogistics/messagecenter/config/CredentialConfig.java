package com.crmforlogistics.messagecenter.config;

import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CredentialConfig {
    @Bean
    public CredentialCipher credentialCipher(
            @Value("${credential.master-key:${CREDENTIAL_MASTER_KEY:}}") String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("CREDENTIAL_MASTER_KEY_REQUIRED");
        }
        return CredentialCipher.fromBase64Key(key.trim());
    }
}
