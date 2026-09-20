package com.crmforlogistics.messagecenter.service.conversation;

import com.crmforlogistics.messagecenter.entity.ConversationPreferenceEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationPreferenceMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ConversationPreferenceServiceTest {
    @Test
    void orderRequestUsesNeighborPlacementInsteadOfClientOwnedRanks() {
        assertThat(java.util.Arrays.stream(
                com.crmforlogistics.messagecenter.dto.request.ConversationPreferenceRequest.OrderRequest.class
                        .getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("sourceType", "sourceId", "targetType", "targetId", "placement");
    }

    @Test
    void hiddenTimestampIsOwnedByTheDatabaseMapper() {
        assertThat(java.util.Arrays.stream(ConversationPreferenceMapper.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .contains("setHiddenNow")
                .doesNotContain("setHiddenAt");
    }

    @Test
    void advisoryLockQueryMapsAnExplicitConstantInsteadOfPostgresVoid() throws NoSuchMethodException {
        assertThat(ConversationPreferenceMapper.class
                .getMethod("lockForUser", UUID.class)
                .getReturnType())
                .isEqualTo(Integer.class);
        assertThat(ConversationPreferenceMapper.class
                .getMethod("lockForUser", UUID.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class)
                .value()[0])
                .contains("select 1 from pg_advisory_xact_lock");
    }

    @Test
    void togglesPinForAccessibleContactOnlyForCurrentUser() {
        ConversationPreferenceMapper preferences = mock(ConversationPreferenceMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        var service = new ConversationPreferenceService(preferences, contacts, conversations);
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(contacts.findAccessibleById(eq(contactId), eq(userId), anyBoolean())).thenReturn(java.util.Optional.of(new com.crmforlogistics.messagecenter.entity.ContactEntity()));
        when(preferences.find(userId, "CONTACT", contactId)).thenReturn(null);
        when(preferences.setPinned(userId, "CONTACT", contactId, true)).thenReturn(1);

        var result = service.togglePinned(userId, "CONTACT", contactId);

        assertThat(result.pinned()).isTrue();
        verify(preferences).ensure(userId, "CONTACT", contactId);
        verify(preferences).setPinned(userId, "CONTACT", contactId, true);
    }

    @Test
    void deleteStoresHiddenTimestampAndDoesNotDeleteConversationData() {
        ConversationPreferenceMapper preferences = mock(ConversationPreferenceMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        var service = new ConversationPreferenceService(preferences, contacts, conversations);
        UUID userId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(conversations.findAccessibleWeComGroup(userId, groupId)).thenReturn(mock(ConversationMapper.WeComSourceConversationAccessRow.class));
        when(preferences.setHiddenNow(userId, "WECOM_GROUP", groupId)).thenReturn(1);

        service.hide(userId, "WECOM_GROUP", groupId);

        verify(preferences).ensure(userId, "WECOM_GROUP", groupId);
        verify(preferences).setHiddenNow(userId, "WECOM_GROUP", groupId);
        verify(conversations).findAccessibleWeComGroup(userId, groupId);
        verifyNoInteractions(contacts);
        verifyNoMoreInteractions(conversations);
    }

    @Test
    void openingAHiddenContactRestoresVisibilityWithoutCreatingAPreferenceRow() {
        ConversationPreferenceMapper preferences = mock(ConversationPreferenceMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        var service = new ConversationPreferenceService(preferences, contacts, conversations);
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(contacts.findAccessibleById(eq(contactId), eq(userId), anyBoolean()))
                .thenReturn(java.util.Optional.of(new com.crmforlogistics.messagecenter.entity.ContactEntity()));
        when(preferences.clearHidden(userId, "CONTACT", contactId)).thenReturn(1);
        var restored = new ConversationPreferenceEntity();
        restored.setHiddenAt(null);
        restored.setPinned(true);
        when(preferences.find(userId, "CONTACT", contactId)).thenReturn(restored);

        var result = service.restore(userId, "CONTACT", contactId);

        assertThat(result.hidden()).isFalse();
        assertThat(result.pinned()).isTrue();
        verify(preferences).clearHidden(userId, "CONTACT", contactId);
        verify(preferences, never()).ensure(any(), anyString(), any());
        verify(preferences, never()).setHiddenNow(any(), anyString(), any());
        verify(preferences, never()).setPinned(any(), anyString(), any(), anyBoolean());
        verify(conversations, never()).listUnified(any(), any(), anyBoolean(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void restoreIsIdempotentForContactsThatWereNeverHidden() {
        ConversationPreferenceMapper preferences = mock(ConversationPreferenceMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        var service = new ConversationPreferenceService(preferences, contacts, conversations);
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(contacts.findAccessibleById(eq(contactId), eq(userId), anyBoolean()))
                .thenReturn(java.util.Optional.of(new com.crmforlogistics.messagecenter.entity.ContactEntity()));
        when(preferences.clearHidden(userId, "CONTACT", contactId)).thenReturn(0);
        when(preferences.find(userId, "CONTACT", contactId)).thenReturn(null);

        var result = service.restore(userId, "CONTACT", contactId);

        assertThat(result.hidden()).isFalse();
        assertThat(result.pinned()).isFalse();
        verify(preferences, never()).ensure(any(), anyString(), any());
    }

    @Test
    void restoreRejectsConversationsTheCurrentAccountCannotOpen() {
        ConversationPreferenceMapper preferences = mock(ConversationPreferenceMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        var service = new ConversationPreferenceService(preferences, contacts, conversations);
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(contacts.findAccessibleById(eq(contactId), eq(userId), anyBoolean()))
                .thenReturn(java.util.Optional.empty());

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.restore(userId, "CONTACT", contactId)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(preferences, never()).clearHidden(any(), anyString(), any());
    }

    @Test
    void reorderMaterializesTheCompleteServerOwnedSequence() {        ConversationPreferenceMapper preferences = mock(ConversationPreferenceMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        var service = new ConversationPreferenceService(preferences, contacts, conversations);
        UUID userId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(contacts.findAccessibleById(any(), eq(userId), anyBoolean()))
                .thenReturn(java.util.Optional.of(new com.crmforlogistics.messagecenter.entity.ContactEntity()));
        var first = row(firstId, Instant.parse("2026-09-03T10:00:00Z"), false);
        var second = row(secondId, Instant.parse("2026-09-03T09:00:00Z"), false);
        when(conversations.listUnified(userId, null, false, null, null, null, null, 201))
                .thenReturn(List.of(first, second));
        when(preferences.setSortRank(eq(userId), anyString(), any(), anyLong())).thenReturn(1);

        service.reorder(userId, "CONTACT", secondId, "CONTACT", firstId, "BEFORE");

        verify(preferences).lockForUser(userId);
        verify(preferences).clearSortRanks(userId);
        var ordered = inOrder(preferences);
        ordered.verify(preferences).setSortRank(userId, "CONTACT", secondId, 0);
        ordered.verify(preferences).setSortRank(userId, "CONTACT", firstId, 1);
    }

    private static ConversationMapper.UnifiedConversationRow row(UUID id, Instant sortAt, boolean pinned) {
        return new ConversationMapper.UnifiedConversationRow("CONTACT", id, "客户", null, null,
                "email", sortAt, "消息", 1, 0, null, 0, pinned, null, sortAt, "CONTACT:" + id);
    }
}
