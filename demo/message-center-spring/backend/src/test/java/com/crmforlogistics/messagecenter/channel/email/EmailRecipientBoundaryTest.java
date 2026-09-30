package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.EmailSubmissionMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class EmailRecipientBoundaryTest {
    private final UUID owner = UUID.randomUUID();
    private final AppConfig config = mock(AppConfig.class);
    private final ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
    private final ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final EmailSubmissionMapper submissions = mock(EmailSubmissionMapper.class);
    private final EmailAttachmentStore attachments = mock(EmailAttachmentStore.class);
    private final AtomicInteger smtpCalls = new AtomicInteger();
    private final EmailSendService service = new EmailSendService(config, messages, conversations,
            accounts, identities, attachments, null, null, submissions,
            (session, host, port, user, password, message) -> smtpCalls.incrementAndGet());

    @Test
    void unknownRecipientIsPersistedAsAnOrphanIdentityInsteadOfBeingRejected() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setOwnerUserId(owner);
        account.setChannelType("email");
        when(accounts.findByOwnerAndChannelType(owner, "email")).thenReturn(List.of(account));
        when(config.smtpHost()).thenReturn("smtp.example.test");
        when(config.smtpPort()).thenReturn("465");
        when(config.smtpUser()).thenReturn("owner@example.test");
        when(config.smtpPassword()).thenReturn("secret");
        when(config.mailFrom()).thenReturn("owner@example.test");
        when(identities.findSendableEmailRecipient(account.getId(), "unknown@example.test"))
                .thenReturn(java.util.Optional.empty());
        // 建完之后重查仍取不到：失败点后移到持久化，而不再是「收件人不在通讯录」。
        when(identities.findByNormalizedValueInScope("email", account.getId().toString(), "unknown@example.test"))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.send(owner, "unknown@example.test", "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SUBMISSION_PERSIST_FAILED");

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.crmforlogistics.messagecenter.entity.ContactIdentityEntity.class);
        verify(identities).insertIfAbsent(captor.capture());
        var created = captor.getValue();
        // 孤立身份：挂在选中的邮箱账号 scope 下，但不创建、也不指向任何联系人。
        assertThat(created.getContactId()).isNull();
        assertThat(created.getChannelType()).isEqualTo("email");
        assertThat(created.getIdentityScope()).isEqualTo(account.getId().toString());
        assertThat(created.getNormalizedValue()).isEqualTo("unknown@example.test");
        assertThat(created.getSource()).isEqualTo("manual");
        verifyNoInteractions(submissions, messages, conversations, attachments);
        assertThat(smtpCalls).hasValue(0);
    }

    @Test
    void missingOwnerIsRejectedBeforeAccountLookupOrWrites() {
        assertThatThrownBy(() -> service.send((UUID) null, "person@example.test", "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("AUTHENTICATION_REQUIRED");
        verifyNoInteractions(accounts, identities, submissions, messages, conversations, attachments);
        assertThat(smtpCalls).hasValue(0);
    }

    @Test
    void ownerlessOverloadsCannotBypassAuthentication() {
        assertThatThrownBy(() -> service.send("person@example.test", "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("AUTHENTICATION_REQUIRED");
        assertThatThrownBy(() -> service.send("person@example.test", "subject", "body", List.of()))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("AUTHENTICATION_REQUIRED");
        verifyNoInteractions(accounts, identities, submissions, messages, conversations, attachments);
        assertThat(smtpCalls).hasValue(0);
    }

    @Test
    void recipientIsResolvedByNormalizedMailboxForTheSelectedAccountBeforeReadingAttachments() {
        ChannelAccountEntity account = ownedAccount();
        AtomicInteger attachmentReads = new AtomicInteger();
        EmailAttachmentInput attachment = new EmailAttachmentInput("file.txt", "text/plain", 1,
                () -> {
                    attachmentReads.incrementAndGet();
                    return new java.io.ByteArrayInputStream(new byte[]{1});
                });
        when(identities.findSendableEmailRecipient(account.getId(), "person@example.test"))
                .thenReturn(java.util.Optional.empty());
        when(identities.findByNormalizedValueInScope("email", account.getId().toString(), "person@example.test"))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.send(owner, " Person <PERSON@Example.test> ",
                "subject", "body", List.of(attachment)))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SUBMISSION_PERSIST_FAILED");

        // 地址先被归一化，且用的是本次实际选中的那个邮箱账号；这一步必须早于读附件。
        verify(identities).findSendableEmailRecipient(account.getId(), "person@example.test");
        verifyNoInteractions(submissions, messages, conversations, attachments);
        assertThat(attachmentReads).hasValue(0);
        assertThat(smtpCalls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"person@example.test, unknown@example.test",
            "Friends: person@example.test;", "person@example.test; unknown@example.test"})
    void multipleOrGroupedAddressesCannotAuthorizeOnlyTheFirstMailbox(String recipient) {
        ownedAccount();

        assertThatThrownBy(() -> service.send(owner, recipient, "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_RECIPIENT_NOT_FOUND");

        verifyNoInteractions(identities, submissions, messages, conversations, attachments);
        assertThat(smtpCalls).hasValue(0);
    }

    @Test
    void existingOrphanIdentityIsReusedAndNoSecondIdentityIsCreated() {
        ChannelAccountEntity account = ownedAccount();
        var orphan = new com.crmforlogistics.messagecenter.entity.ContactIdentityEntity();
        orphan.setId(UUID.randomUUID());
        when(identities.findSendableEmailRecipient(account.getId(), "orphan@example.test"))
                .thenReturn(java.util.Optional.of(orphan));

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.send(owner, "orphan@example.test", "subject", "body"));

        // 这条只钉住「复用而不是新建」这一点：孤立身份不再被当成「收件人不在通讯录」拒掉，
        // 所以失败点必然落在收件人校验之后（落库/SMTP 那一侧）。
        // 完整的端到端证明在 EmailRecipientDatabaseTest，这里不铺 SMTP 配置链。
        assertThat(failure instanceof EmailException ? ((EmailException) failure).code() : null)
                .isNotEqualTo("EMAIL_RECIPIENT_NOT_FOUND");
        verify(identities).findSendableEmailRecipient(account.getId(), "orphan@example.test");
        verify(identities, never()).insertIfAbsent(
                any(com.crmforlogistics.messagecenter.entity.ContactIdentityEntity.class));
    }

    private ChannelAccountEntity ownedAccount() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setOwnerUserId(owner);
        account.setChannelType("email");
        when(accounts.findByOwnerAndChannelType(owner, "email")).thenReturn(List.of(account));
        return account;
    }
}
