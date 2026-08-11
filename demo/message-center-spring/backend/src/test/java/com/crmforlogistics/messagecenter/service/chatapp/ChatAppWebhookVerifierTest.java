package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAppWebhookVerifierTest {
    @Test
    void acceptsValidSignatureWithinTimeWindowAndRejectsInvalidSignature() {
        AppConfig config = mock(AppConfig.class);
        when(config.chatappWebhookSecret()).thenReturn("secret");
        when(config.chatappWebhookMaxSkewSeconds()).thenReturn(300);
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
        ChatAppWebhookVerifier verifier = new ChatAppWebhookVerifier(config, clock);
        String timestamp = "1800000000";
        String body = "{\"MessageId\":\"event-1\"}";
        String signature = verifier.signForTest(timestamp, body);

        assertThatCode(() -> verifier.verify(signature, timestamp, body)).doesNotThrowAnyException();
        assertThatThrownBy(() -> verifier.verify("bad", timestamp, body))
                .isInstanceOf(ChatAppWebhookAuthenticationException.class);
    }
}
