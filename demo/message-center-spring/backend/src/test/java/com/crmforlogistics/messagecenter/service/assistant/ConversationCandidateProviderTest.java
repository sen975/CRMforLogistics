package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationCandidateProviderTest {
    @Test
    void searchUsesCurrentUserAndExcludesMessageBodyAndProviderIdentity() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        ConversationMapper mapper = mock(ConversationMapper.class);
        when(mapper.listUnified(eq(userId), eq("客户"), eq(false), isNull(), isNull(), isNull(),
                isNull(), eq(ConversationCandidateProvider.LIMIT))).thenReturn(List.of(
                new ConversationMapper.UnifiedConversationRow("CONTACT", id, "客户", "私有备注",
                        "https://avatar.invalid/private", "chatapp,email", Instant.parse("2026-09-01T00:00:00Z"),
                        "消息正文不应进候选", 4, 1, "provider-private-id", 0, false)));

        ConversationCandidates result = new ConversationCandidateProvider(mapper).search(userId, "客户");

        assertThat(result.items()).hasSize(1);
        ConversationCandidates.Item item = result.items().get(0);
        assertThat(item.channels()).containsExactly("chatapp", "email");
        assertThat(item.toString()).doesNotContain("消息正文不应进候选", "provider-private-id",
                "https://avatar.invalid/private", "私有备注");
        verify(mapper).listUnified(userId, "客户", false, null, null, null, null,
                ConversationCandidateProvider.LIMIT);
    }
}
