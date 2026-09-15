package com.crmforlogistics.messagecenter.service.conversation;

import com.crmforlogistics.messagecenter.dto.request.SearchMode;
import com.crmforlogistics.messagecenter.dto.response.ConversationListItemResponse;
import com.crmforlogistics.messagecenter.dto.response.ConversationPageResponse;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.service.contact.ContactTagMatchResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnifiedConversationServiceTest {

    @Test
    void returnsContactsAndGroupsInOneStablePageWithoutMergingGroups() {
        UUID userId = UUID.randomUUID();
        ConversationMapper mapper = mock(ConversationMapper.class);
        ConversationListItemResponse contact = ConversationListItemResponse.contact(
                UUID.randomUUID(), "客户 A", "重点客户", Instant.parse("2026-08-25T10:00:00Z"), "a", 4, 1);
        ConversationListItemResponse groupA = ConversationListItemResponse.group(
                UUID.randomUUID(), "群 A", "chat-a", Instant.parse("2026-08-25T09:00:00Z"), "ga", 2, 3, 0);
        ConversationListItemResponse groupB = ConversationListItemResponse.group(
                UUID.randomUUID(), "群 B", "chat-b", Instant.parse("2026-08-25T08:00:00Z"), "gb", 2, 1, 0);
        when(mapper.listUnified(eq(userId), eq(null), eq(false), eq(null), eq(null), eq(null), eq(null), eq(11)))
                .thenReturn(List.of(
                        new ConversationMapper.UnifiedConversationRow(contact.type(), contact.id(), contact.displayName(), contact.remark(), null, "", contact.lastMessageAt(), contact.lastText(), contact.messageCount(), contact.unreadCount(), null, 0, false),
                        new ConversationMapper.UnifiedConversationRow(groupA.type(), groupA.id(), groupA.displayName(), null, "wecom", groupA.lastMessageAt(), groupA.lastText(), groupA.messageCount(), groupA.unreadCount(), groupA.providerConversationKey(), groupA.participantCount()),
                        new ConversationMapper.UnifiedConversationRow(groupB.type(), groupB.id(), groupB.displayName(), null, "wecom", groupB.lastMessageAt(), groupB.lastText(), groupB.messageCount(), groupB.unreadCount(), groupB.providerConversationKey(), groupB.participantCount())));

        UnifiedConversationService service = new UnifiedConversationService(mapper,
                new ContactTagMatchResolver(mock(ContactTagMapper.class)));
        ConversationPageResponse result = service.list(userId, null, SearchMode.CONTACT, null, 10);

        assertThat(result.records()).containsExactly(contact, groupA, groupB);
        assertThat(result.records()).extracting(ConversationListItemResponse::type)
                .containsExactly("CONTACT", "WECOM_GROUP", "WECOM_GROUP");
        assertThat(result.records()).extracting(ConversationListItemResponse::id)
                .doesNotHaveDuplicates();
    }

    @Test
    void passesCursorAndSearchToServerSideQuery() {
        UUID userId = UUID.randomUUID();
        ConversationMapper mapper = mock(ConversationMapper.class);
        UUID rowId = UUID.randomUUID();
        Instant sortAt = Instant.parse("2026-08-25T10:00:00Z");
        var cursorRow = new ConversationMapper.UnifiedConversationRow(
                "CONTACT", rowId, "Alice", null, null, "email", sortAt, "a",
                1, 0, null, 0, true, 7L, sortAt, "CONTACT:" + rowId);
        String cursor = UnifiedConversationService.encodeCursor(cursorRow);
        when(mapper.listUnified(eq(userId), eq("alice"), eq(false), eq(true), eq(7L),
                eq("2026-08-25T10:00:00Z"), eq("CONTACT:" + rowId), eq(21)))
                .thenReturn(List.of());

        UnifiedConversationService service = new UnifiedConversationService(mapper,
                new ContactTagMatchResolver(mock(ContactTagMapper.class)));
        service.list(userId, "alice", SearchMode.CONTACT, cursor, 20);

        org.mockito.Mockito.verify(mapper).listUnified(
                userId, "alice", false, true, 7L, "2026-08-25T10:00:00Z", "CONTACT:" + rowId, 21);
    }

    @Test
    void backfillsMatchedTagsForVisibleContactsOnlyAndScopesOwner() {
        UUID userId = UUID.randomUUID();
        ConversationMapper mapper = mock(ConversationMapper.class);
        UUID matchedId = UUID.randomUUID();
        UUID unmatchedId = UUID.randomUUID();
        UUID lookaheadId = UUID.randomUUID();
        Instant sortAt = Instant.parse("2026-08-25T10:00:00Z");
        when(mapper.listUnified(eq(userId), eq("客户"), eq(true), eq(null), eq(null), eq(null), eq(null), eq(3)))
                .thenReturn(List.of(
                        new ConversationMapper.UnifiedConversationRow("CONTACT", matchedId, "客户 A", null, null, sortAt, "a", 4, 1, null, 0),
                        new ConversationMapper.UnifiedConversationRow("CONTACT", unmatchedId, "客户 B", null, null, sortAt.minusSeconds(1), "b", 4, 1, null, 0),
                        new ConversationMapper.UnifiedConversationRow("CONTACT", lookaheadId, "客户 C", null, null, sortAt.minusSeconds(2), "c", 4, 1, null, 0)));

        ContactTagMapper tagMapper = mock(ContactTagMapper.class);
        when(tagMapper.findMatchedByContactIds(eq(userId), any(), eq("客户")))
                .thenReturn(List.of(new ContactTagMapper.MatchedTagRow(matchedId, "重点客户")));
        UnifiedConversationService service = new UnifiedConversationService(mapper,
                new ContactTagMatchResolver(tagMapper));

        ConversationPageResponse result = service.list(userId, "客户", SearchMode.TAG, null, 2);

        assertThat(result.records()).extracting(ConversationListItemResponse::id)
                .containsExactly(matchedId, unmatchedId);
        assertThat(result.records().get(0).matchedTags()).containsExactly("重点客户");
        assertThat(result.records().get(1).matchedTags()).isEmpty();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(tagMapper).findMatchedByContactIds(eq(userId), idsCaptor.capture(), eq("客户"));
        assertThat(idsCaptor.getValue())
                .containsExactlyInAnyOrder(matchedId, unmatchedId)
                .doesNotContain(lookaheadId);
    }
}
