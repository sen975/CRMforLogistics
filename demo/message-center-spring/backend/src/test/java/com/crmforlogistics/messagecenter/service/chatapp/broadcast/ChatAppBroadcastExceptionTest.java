package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAppBroadcastExceptionTest {
    @Test
    void sanitizesProviderDiagnosticBeforeItCanBePersisted() {
        ChatAppBroadcastException error = new ChatAppBroadcastException(
                "CHATAPP_BROADCAST_PROVIDER_REJECTED", HttpStatus.BAD_GATEWAY,
                false, false, "InvalidParameter", "request-1",
                "Authorization: Bearer secret AccessKeySecret=topsecret phone=60111111111", null);

        assertThat(error.safeMessage())
                .doesNotContain("Bearer secret", "topsecret", "60111111111")
                .contains("[REDACTED]");
    }
}
