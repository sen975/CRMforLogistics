package com.crmforlogistics.messagecenter.service.conversation;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.dto.response.ConversationListItemResponse;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnifiedConversationServiceTest {

    @Test
    void returnsContactsAndGroupsInOneStablePageWithoutMergingGroups() {
        UUID userId = UUID.randomUUID();
        ConversationMapper mapper = mock(ConversationMapper.class);
        ConversationListItemResponse contact = ConversationListItemResponse.contact(
                UUID.randomUUID(), "客户 A", Instant.parse("2026-08-25T10:00:00Z"), "a", 4, 1);
        ConversationListItemResponse groupA = ConversationListItemResponse.group(
                UUID.randomUUID(), "群 A", "chat-a", Instant.parse("2026-08-25T09:00:00Z"), "ga", 2, 3, 0);
        ConversationListItemResponse groupB = ConversationListItemResponse.group(
                UUID.randomUUID(), "群 B", "chat-b", Instant.parse("2026-08-25T08:00:00Z"), "gb", 2, 1, 0);
        when(mapper.listUnified(eq(userId), eq(null), eq(null), eq(null), eq(10)))
                .thenReturn(List.of(
                        new ConversationMapper.UnifiedConversationRow(contact.type(), contact.id(), contact.displayName(), null, "", contact.lastMessageAt(), contact.lastText(), contact.messageCount(), contact.unreadCount(), null, 0),
                        new ConversationMapper.UnifiedConversationRow(groupA.type(), groupA.id(), groupA.displayName(), null, "wecom", groupA.lastMessageAt(), groupA.lastText(), groupA.messageCount(), groupA.unreadCount(), groupA.providerConversationKey(), groupA.participantCount()),
                        new ConversationMapper.UnifiedConversationRow(groupB.type(), groupB.id(), groupB.displayName(), null, "wecom", groupB.lastMessageAt(), groupB.lastText(), groupB.messageCount(), groupB.unreadCount(), groupB.providerConversationKey(), groupB.participantCount())));

        UnifiedConversationService service = new UnifiedConversationService(mapper);
        Page<ConversationListItemResponse> result = service.list(userId, null, null, 10);

        assertThat(result.getRecords()).containsExactly(contact, groupA, groupB);
        assertThat(result.getRecords()).extracting(ConversationListItemResponse::type)
                .containsExactly("CONTACT", "WECOM_GROUP", "WECOM_GROUP");
        assertThat(result.getRecords()).extracting(ConversationListItemResponse::id)
                .doesNotHaveDuplicates();
    }

    @Test
    void passesCursorAndSearchToServerSideQuery() {
        UUID userId = UUID.randomUUID();
        ConversationMapper mapper = mock(ConversationMapper.class);
        when(mapper.listUnified(eq(userId), eq("alice"), eq("2026-08-25T10:00:00Z"), eq("CONTACT:"), eq(20)))
                .thenReturn(List.of());

        UnifiedConversationService service = new UnifiedConversationService(mapper);
        service.list(userId, "alice", "2026-08-25T10:00:00Z|CONTACT:", 20);

        org.mockito.Mockito.verify(mapper).listUnified(
                userId, "alice", "2026-08-25T10:00:00Z", "CONTACT:", 20);
    }
}
