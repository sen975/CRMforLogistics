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

    /**
     * 授权结果里带回被操作会话的显示名（助手执行回话要用它说人话），
     * 而<b>没有</b>显示名的会话照样是「可以操作」—— 两者不是同一个判断。
     *
     * <p>守卫的是一处很细的塌缩：若实现写成
     * {@code findAccessibleById(...).map(ContactEntity::getDisplayName).orElseThrow(...)}，
     * 那么 {@code Optional.map} 在映射出 {@code null} 时会交出 empty，于是「存在但无名」
     * 被判成「不存在」—— 授权范围被无声明地缩小，而两者只差一个 map 调用。
     * 这条用例就是那次塌缩的回归守卫（它当时就是这么被抓到的）。
     */
    @Test
    void aContactWithoutADisplayNameIsStillAuthorized() {
        ConversationPreferenceMapper preferences = mock(ConversationPreferenceMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        var service = new ConversationPreferenceService(preferences, contacts, conversations);
        UUID userId = UUID.randomUUID();
        UUID named = UUID.randomUUID();
        UUID anonymous = UUID.randomUUID();
        var withName = new com.crmforlogistics.messagecenter.entity.ContactEntity();
        withName.setDisplayName("悦为小森");
        when(contacts.findAccessibleById(eq(named), eq(userId), anyBoolean()))
                .thenReturn(java.util.Optional.of(withName));
        when(contacts.findAccessibleById(eq(anonymous), eq(userId), anyBoolean()))
                .thenReturn(java.util.Optional.of(new com.crmforlogistics.messagecenter.entity.ContactEntity()));
        when(preferences.find(eq(userId), anyString(), any())).thenReturn(null);
        when(preferences.setPinned(eq(userId), anyString(), any(), anyBoolean())).thenReturn(1);

        var namedResult = service.setPinned(userId, "CONTACT", named, true);
        var anonymousResult = service.setPinned(userId, "CONTACT", anonymous, true);

        assertThat(namedResult.displayName()).isEqualTo("悦为小森");
        assertThat(anonymousResult.displayName())
                .as("拿不到名字就给 null，由调用方退回显示 id —— 但不许把它当成「找不到」")
                .isNull();
        assertThat(anonymousResult.pinned()).isTrue();
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
