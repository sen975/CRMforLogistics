package com.crmforlogistics.messagecenter.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageResponseSourceConversationContractTest {

    @Test
    void exposesWeComSourceConversationIdentity() {
        UUID sourceConversationId = UUID.randomUUID();
        MessageResponse response = new MessageResponse(
                UUID.randomUUID(),
                "provider-message",
                "inbound",
                "text",
                "",
                "",
                "",
                "wecom",
                "sender",
                "receiver",
                Instant.parse("2026-08-25T00:00:00Z"),
                "delivered",
                1,
                List.of(),
                sourceConversationId,
                "GROUP",
                "销售群");

        assertThat(response.sourceConversationId()).isEqualTo(sourceConversationId);
        assertThat(response.conversationType()).isEqualTo("GROUP");
        assertThat(response.conversationDisplayName()).isEqualTo("销售群");
    }
}
