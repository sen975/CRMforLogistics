package com.crmforlogistics.messagecenter.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialConfigTest {
    private final CredentialConfig config = new CredentialConfig();

    @Test
    void rejectsMissingMasterKey() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> config.credentialCipher("  "));

        assertEquals("CREDENTIAL_MASTER_KEY_REQUIRED", error.getMessage());
    }

    @Test
    void createsCipherFromConfiguredMasterKey() {
        String testKey = Base64.getEncoder().encodeToString(new byte[32]);

        assertDoesNotThrow(() -> config.credentialCipher(testKey));
    }

    @Test
    void springContextReadsCredentialMasterKeyEnvironmentProperty() {
        String testKey = Base64.getEncoder().encodeToString(new byte[32]);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context, "CREDENTIAL_MASTER_KEY=" + testKey);
            context.register(CredentialConfig.class);
            context.refresh();

            assertDoesNotThrow(() -> context.getBean(
                    com.crmforlogistics.messagecenter.infrastructure.CredentialCipher.class));
        }
    }
}
