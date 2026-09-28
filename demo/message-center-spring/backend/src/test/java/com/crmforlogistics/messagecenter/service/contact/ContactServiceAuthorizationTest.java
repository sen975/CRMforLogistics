package com.crmforlogistics.messagecenter.service.contact;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.dto.request.SearchMode;
import com.crmforlogistics.messagecenter.dto.response.ContactResponse;
import com.crmforlogistics.messagecenter.dto.response.ContactTagResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppAccountResolver;
import com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryQueryService;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactServiceAuthorizationTest {

    @Test
    void listProjectionOnlyAggregatesConversationsAccessibleToCurrentUser() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactEntity contact = contact(contactId);
        contact.setCreatedBy(userId);
        ContactIdentityEntity identity = identity(contactId);
        ConversationEntity accessible = conversation(identity.getId(), Instant.parse("2026-08-06T09:00:00Z"));
        ConversationEntity foreign = conversation(identity.getId(), Instant.parse("2026-08-07T09:00:00Z"));

        ContactMapper contactMapper = mock(ContactMapper.class);
        ContactIdentityMapper identityMapper = mock(ContactIdentityMapper.class);
        ConversationMapper conversationMapper = mock(ConversationMapper.class, invocation -> {
            if (invocation.getMethod().getName().equals("listAccessibleForContact")) {
                assertThat(invocation.getArguments()).containsExactly(contactId, userId);
                return List.of(accessible);
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        MessageMapper messageMapper = mock(MessageMapper.class);
        ContactTagMapper tagMapper = mock(ContactTagMapper.class);
        when(tagMapper.findActiveByContactId(contactId)).thenReturn(List.of(
                new ContactTagResponse(UUID.randomUUID(), "重点跟进", "blue")));
        when(tagMapper.findActiveByContactIdAndOwner(contactId, userId)).thenReturn(List.of(
                new ContactTagResponse(UUID.randomUUID(), "重点跟进", "blue")));

        Page<ContactEntity> contacts = new Page<>(1, 20);
        contacts.setRecords(List.of(contact));
        contacts.setTotal(1);
        when(contactMapper.listForUser(any(), any(), any(), anyBoolean(), any(),
                any(), any(Boolean.class), any(), any())).thenReturn(contacts);
        when(identityMapper.findByContactId(contactId)).thenReturn(List.of(identity));
        MessageEntity lastMessage = new MessageEntity();
        lastMessage.setBodyText("accessible message");
        when(messageMapper.selectOne(any())).thenReturn(lastMessage);
        when(messageMapper.selectCount(any())).thenReturn(7L, 2L);

        ContactService service = new ContactService(
                contactMapper, identityMapper, conversationMapper, messageMapper,
                mock(ChatAppAccountResolver.class), tagMapper);

        ContactResponse result = service.listForUser(userId, null, null, null, 1, 20)
                .getRecords().get(0);

        assertThat(result.lastMessageAt()).isEqualTo(accessible.getLastMessageAt());
        assertThat(result.lastText()).isEqualTo("accessible message");
        assertThat(result.messageCount()).isEqualTo(7);
        assertThat(result.unreadCount()).isEqualTo(2);
        assertThat(result.tags()).extracting(ContactTagResponse::name)
                .containsExactly("重点跟进");

        verify(messageMapper).selectOne(any());
        verify(messageMapper, org.mockito.Mockito.times(2)).selectCount(any());
        verify(contactMapper).listForUser(any(), eq(userId), isNull(), eq(false), isNull(),
                isNull(), eq(false), isNull(), isNull());
    }

    @Test
    void getByIdRejectsAContactThatIsNotAccessibleToTheUser() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactEntity foreignContact = contact(contactId);

        ContactMapper contactMapper = mock(ContactMapper.class, invocation -> {
            if (invocation.getMethod().getName().equals("findAccessibleById")) {
                assertThat(invocation.getArguments()).containsExactly(contactId, userId, false);
                return Optional.empty();
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });

        ContactService service = new ContactService(contactMapper,
                mock(ContactIdentityMapper.class), mock(ConversationMapper.class),
                mock(MessageMapper.class), mock(ChatAppAccountResolver.class));

        assertThatThrownBy(() -> service.getById(userId, contactId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Contact not found");
    }

    @Test
    void authorizedChannelTypesUseOnlyScopedIdentitiesWithoutExposingAddresses() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactIdentityEntity email = identity(contactId);
        email.setChannelType("email");
        email.setIdentityValue("secret@example.invalid");
        ContactIdentityEntity duplicate = identity(contactId);
        duplicate.setChannelType("email");
        ContactIdentityEntity chatapp = identity(contactId);
        ContactIdentityMapper identityMapper = mock(ContactIdentityMapper.class);
        when(identityMapper.findByContactIdAndOwner(contactId, userId))
                .thenReturn(List.of(email, duplicate, chatapp));
        when(identityMapper.findByContactId(contactId)).thenReturn(List.of(identity(contactId)));
        ContactService service = new ContactService(mock(ContactMapper.class), identityMapper,
                mock(ConversationMapper.class), mock(MessageMapper.class),
                mock(ChatAppAccountResolver.class));

        assertThat(service.listAuthorizedChannelTypes(userId, contactId))
                .containsExactly("chatapp", "email");
        verify(identityMapper).findByContactIdAndOwner(contactId, userId);
        verify(identityMapper, never()).findByContactId(contactId);
    }

    @Test
    void authorizedChannelProfilesExposeBoundedIdentityValuesWithoutInternalScopeOrCredentials() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactIdentityEntity email = identity(contactId);
        email.setChannelType("email");
        email.setIdentityScope("private-scope-id");
        email.setIdentityValue("buyer@example.invalid");
        email.setNormalizedValue("buyer@example.invalid");
        email.setDisplayName("采购邮箱");
        ContactIdentityEntity chatapp = identity(contactId);
        chatapp.setChannelType("chatapp");
        chatapp.setIdentityScope("00000000-0000-0000-0000-000000000042");
        chatapp.setIdentityValue("15550001111");
        chatapp.setDisplayName("王经理");
        ContactIdentityMapper identityMapper = mock(ContactIdentityMapper.class);
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        when(identityMapper.findByContactIdAndOwner(contactId, userId)).thenReturn(List.of(email, chatapp));
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.fromString("00000000-0000-0000-0000-000000000042"));
        account.setName("海外 ChatApp");
        when(accountMapper.findByIdAndOwner(UUID.fromString("00000000-0000-0000-0000-000000000042"), userId))
                .thenReturn(account);
        ContactService service = new ContactService(mock(ContactMapper.class), identityMapper,
                mock(ConversationMapper.class), mock(MessageMapper.class), mock(ChatAppAccountResolver.class),
                accountMapper);

        assertThat(service.listAuthorizedChannelProfiles(userId, contactId))
                .extracting(ContactService.AuthorizedChannelProfile::channelType)
                .containsExactly("email", "chatapp");
        assertThat(service.listAuthorizedChannelProfiles(userId, contactId))
                .containsExactly(
                        new ContactService.AuthorizedChannelProfile("email", "buyer@example.invalid", "采购邮箱", null),
                        new ContactService.AuthorizedChannelProfile("chatapp", "15550001111", "王经理", "海外 ChatApp"));
        verify(identityMapper, org.mockito.Mockito.times(2)).findByContactIdAndOwner(contactId, userId);
        verify(identityMapper, never()).findByContactId(contactId);
    }

    @Test
    void authorizedChannelTypesAreEmptyWithoutVisibleIdentities() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactIdentityMapper identityMapper = mock(ContactIdentityMapper.class);
        when(identityMapper.findByContactIdAndOwner(contactId, userId)).thenReturn(List.of());
        ContactService service = new ContactService(mock(ContactMapper.class), identityMapper,
                mock(ConversationMapper.class), mock(MessageMapper.class),
                mock(ChatAppAccountResolver.class));

        assertThat(service.listAuthorizedChannelTypes(userId, contactId)).isEmpty();
        verify(identityMapper, never()).findByContactId(contactId);
    }

    @Test
    void markReadOnlyUpdatesConversationsAccessibleToTheCurrentUser() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactIdentityEntity identity = identity(contactId);
        ConversationEntity accessible = conversation(identity.getId(), Instant.now());

        ContactIdentityMapper identityMapper = mock(ContactIdentityMapper.class);
        when(identityMapper.findByContactId(contactId)).thenReturn(List.of(identity));
        ConversationMapper conversationMapper = mock(ConversationMapper.class, invocation -> {
            if (invocation.getMethod().getName().equals("listAccessibleForContact")) {
                assertThat(invocation.getArguments()).containsExactly(contactId, userId);
                return List.of(accessible);
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        MessageMapper messageMapper = mock(MessageMapper.class);
        ContactService service = new ContactService(mock(ContactMapper.class),
                identityMapper, conversationMapper, messageMapper,
                mock(ChatAppAccountResolver.class));

        assertThatCode(() -> invokeMarkAsRead(service, userId, contactId))
                .doesNotThrowAnyException();

        verify(messageMapper).markRead(List.of(accessible.getId()));
    }

    @Test
    void contactAccessQueriesUseAssignmentTeamGrantAndAdminSemantics() throws Exception {
        String conversationSql = sql(ConversationMapper.class.getMethod(
                "listAccessibleForContact", UUID.class, UUID.class));
        assertThat(conversationSql)
                .contains("assigned_user_id")
                .contains("team_members")
                .contains("conversation_access_grants")
                .contains("user_roles")
                .contains("r.code = 'admin'");

        String contactSql = sql(ContactMapper.class.getMethod(
                "findAccessibleById", UUID.class, UUID.class, boolean.class));
        assertThat(contactSql)
                .contains("created_by")
                .contains("assigned_user_id")
                .contains("team_members")
                .contains("conversation_access_grants")
                .contains("isAdmin");
    }

    @Test
    void chatAppListingRequiresAnAccountScopedIdentityFilter() throws Exception {
        String contactSql = sql(ContactMapper.class.getMethod(
                "listForUser", com.baomidou.mybatisplus.core.metadata.IPage.class,
                UUID.class, String.class, boolean.class, Instant.class, UUID.class, boolean.class,
                String.class, UUID.class));

        assertThat(contactSql)
                .contains("filtered_ci.channel_type = #{channelType}")
                .contains("filtered_ci.identity_scope = #{channelAccountId}::text")
                .contains("filtered_ci.deleted_at is null")
                .contains("c.created_by = #{userId}::uuid")
                .contains("cv.assigned_user_id = #{userId}::uuid")
                .contains("team_members")
                .contains("conversation_access_grants");
    }

    @Test
    void chatAppSendAccessQueryIncludesIdentityAndActorPermissions() {
        Method method = Arrays.stream(ContactMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals("findAccessibleForChatAppSend"))
                .findFirst()
                .orElse(null);

        assertThat(method).isNotNull();
        String contactSql = sql(method);
        assertThat(contactSql)
                .contains("ci.id = #{identityId}::uuid")
                .contains("ci.channel_type = 'chatapp'")
                .contains("ci.identity_scope = #{channelAccountId}::text")
                .contains("ci.deleted_at is null")
                .contains("c.created_by = #{userId}::uuid")
                .contains("assigned_user_id")
                .contains("team_members")
                .contains("conversation_access_grants")
                .contains("user_roles")
                .contains("r.code = 'admin'");
    }

    @Test
    void chatAppListingRejectsMissingAccountId() throws Exception {
        ContactService service = new ContactService(
                mock(ContactMapper.class), mock(ContactIdentityMapper.class),
                mock(ConversationMapper.class), mock(MessageMapper.class),
                mock(ChatAppAccountResolver.class));

        Method method = ContactService.class.getMethod("listForUser", UUID.class, String.class,
                Instant.class, UUID.class, int.class, int.class, String.class, UUID.class);

        assertThatThrownBy(() -> method.invoke(service, UUID.randomUUID(), null, null, null,
                1, 20, "chatapp", null))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
    }

    @Test
    void chatAppListingPassesCurrentAccountScopeToMapper() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        ContactMapper contactMapper = mock(ContactMapper.class);
        ChatAppAccountResolver accountResolver = mock(ChatAppAccountResolver.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        when(accountResolver.requireOwnedAccount(userId, accountId)).thenReturn(account);
        Page<ContactEntity> contacts = new Page<>(1, 20);
        contacts.setRecords(List.of());
        when(contactMapper.listForUser(any(), any(), any(), anyBoolean(), any(),
                any(), any(Boolean.class), any(), any())).thenReturn(contacts);
        ContactService service = new ContactService(
                contactMapper, mock(ContactIdentityMapper.class),
                mock(ConversationMapper.class), mock(MessageMapper.class), accountResolver);

        service.listForUser(userId, null, null, null,
                1, 20, "chatapp", accountId);

        verify(contactMapper).listForUser(any(), eq(userId), isNull(), eq(false), isNull(),
                isNull(), eq(false), eq("chatapp"), eq(accountId));
        verify(accountResolver).requireOwnedAccount(userId, accountId);
        verify(accountResolver, never()).requireCurrentAccount(accountId);
    }

    @Test
    void chatAppListingRejectsAnActiveAccountThatIsNotTheFixedAccount() {
        UUID accountId = UUID.randomUUID();
        ContactMapper contactMapper = mock(ContactMapper.class);
        ChatAppAccountResolver accountResolver = mock(ChatAppAccountResolver.class);
        UUID userId = UUID.randomUUID();
        when(accountResolver.requireOwnedAccount(userId, accountId))
                .thenThrow(new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE"));
        ContactService service = new ContactService(
                contactMapper, mock(ContactIdentityMapper.class),
                mock(ConversationMapper.class), mock(MessageMapper.class), accountResolver);

        assertThatThrownBy(() -> service.listForUser(userId, null, null, null,
                1, 20, "chatapp", accountId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
    }

    @Test
    void tagModeSearchReturnsMatchedTagNamesOnlyForTaggedContacts() {
        UUID userId = UUID.randomUUID();
        UUID taggedId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        ContactMapper contactMapper = mock(ContactMapper.class);
        ContactTagMatchResolver resolver = mock(ContactTagMatchResolver.class);
        ContactEntity tagged = new ContactEntity();
        tagged.setId(taggedId);
        tagged.setDisplayName("张经理");
        ContactEntity other = new ContactEntity();
        other.setId(otherId);
        other.setDisplayName("李四");
        Page<ContactEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(tagged, other));
        when(contactMapper.listForUser(any(), any(), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(page);
        when(resolver.matchNamesByContact(eq(userId), any(), eq("客户"), eq(SearchMode.TAG)))
                .thenReturn(Map.of(taggedId, List.of("VIP客户")));
        ContactService service = new ContactService(contactMapper,
                mock(ContactIdentityMapper.class), mock(ConversationMapper.class),
                mock(MessageMapper.class), mock(ChatAppAccountResolver.class),
                mock(ContactTagMapper.class), mock(ContactMemoryQueryService.class), resolver);

        List<ContactResponse> records = service
                .listForUser(userId, "客户", SearchMode.TAG, null, null, 1, 20, null, null)
                .getRecords();

        assertThat(records).extracting(ContactResponse::id, ContactResponse::matchedTags)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(taggedId, List.of("VIP客户")),
                        org.assertj.core.groups.Tuple.tuple(otherId, List.of()));

        ArgumentCaptor<UUID> ownerCaptor = ArgumentCaptor.forClass(UUID.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(resolver).matchNamesByContact(ownerCaptor.capture(), idsCaptor.capture(),
                eq("客户"), eq(SearchMode.TAG));
        assertThat(ownerCaptor.getValue()).isEqualTo(userId);
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(taggedId, otherId);
    }

    private static void invokeMarkAsRead(ContactService service, UUID userId, UUID contactId)
            throws Exception {
        Method method = ContactService.class.getMethod("markAsRead", UUID.class, UUID.class);
        method.invoke(service, userId, contactId);
    }

    private static String sql(Method method) {
        return String.join(" ", method.getAnnotation(Select.class).value());
    }

    private static ContactEntity contact(UUID id) {
        ContactEntity contact = new ContactEntity();
        contact.setId(id);
        contact.setDisplayName("Customer");
        return contact;
    }

    private static ContactIdentityEntity identity(UUID contactId) {
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contactId);
        identity.setChannelType("chatapp");
        identity.setIdentityValue("60123456789");
        return identity;
    }

    private static ConversationEntity conversation(UUID identityId, Instant lastMessageAt) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setContactIdentityId(identityId);
        conversation.setLastMessageAt(lastMessageAt);
        return conversation;
    }
}
