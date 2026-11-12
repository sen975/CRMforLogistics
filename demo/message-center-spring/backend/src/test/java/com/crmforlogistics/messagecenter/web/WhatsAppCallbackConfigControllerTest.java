package com.crmforlogistics.messagecenter.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppCallbackConfigControllerTest {
    @Test
    void exposesAdminScopedPhoneAndAccountCallbackRoutes() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppCallbackConfigController.java"));
        assertThat(source)
                .contains("/api/admin/whatsapp/cams/{scopeId}/callbacks")
                .contains("/phones/{channelAccountId}")
                .contains("/account")
                .contains("requireAdmin");
    }
}
