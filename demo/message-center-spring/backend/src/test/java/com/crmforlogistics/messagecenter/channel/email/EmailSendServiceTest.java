package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.EmailSubmissionMapper;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.EmailSubmissionEntity;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailSendServiceTest {

    private final java.util.UUID ownerId = java.util.UUID.randomUUID();

    @Mock AppConfig config;
    @Mock MessageMapper messageMapper;
    @Mock ConversationMapper conversationMapper;
    @Mock ChannelAccountMapper channelAccountMapper;
    @Mock ContactIdentityMapper contactIdentityMapper;
    @Mock AiTopicActivityRecorder topicActivityRecorder;
    @Mock EmailSubmissionMapper submissionMapper;
    @Mock EmailSubmissionLeaseKeeper leaseKeeper;

    @Test
    void staleSubmissionRecoveryIsScopedToRequestOwnerAndBounded() {
        java.util.UUID ownerId = java.util.UUID.randomUUID();
        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, null, null, submissionMapper);

        service.listUnknownSubmissions(ownerId, 500);

        verify(submissionMapper).markStaleSubmissionsUnknown(ownerId, 100);
        verify(submissionMapper).listUnknownByOwner(ownerId, 100);
    }

    @Test
    void persistedOutboundEmailRecordsTopicActivity() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(java.util.UUID.randomUUID());
        account.setChannelType("email");
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(java.util.UUID.randomUUID());
        identity.setContactId(java.util.UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(java.util.UUID.randomUUID());
        when(channelAccountMapper.findByIdAndOwner(account.getId(), ownerId)).thenReturn(account);
        when(contactIdentityMapper.findSendableEmailRecipient(account.getId(), "customer@example.test"))
                .thenReturn(java.util.Optional.of(identity));
        when(conversationMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(conversation));
        when(messageMapper.insertWithSequence(any())).thenReturn(1);

        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, topicActivityRecorder);
        service.persistOutbound(ownerId, account.getId(), "customer@example.test", "subject", "body", "<message@example.test>");

        verify(topicActivityRecorder).recordContact(eq(identity.getContactId()),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void persistedOutboundWithoutLinkedContactIsAllowedButRecordsNoTopicActivity() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(java.util.UUID.randomUUID());
        account.setChannelType("email");
        // 孤立身份：这个收件地址还没有对应联系人（人工发给通讯录外的地址就是这个形态）。
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(java.util.UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(java.util.UUID.randomUUID());
        when(channelAccountMapper.findByIdAndOwner(account.getId(), ownerId)).thenReturn(account);
        when(contactIdentityMapper.findSendableEmailRecipient(account.getId(), "person@example.test"))
                .thenReturn(java.util.Optional.of(identity));
        when(conversationMapper.selectList(any())).thenReturn(java.util.List.of(conversation));
        when(messageMapper.insertWithSequence(any())).thenReturn(1);

        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, topicActivityRecorder);

        service.persistOutbound(ownerId, account.getId(), "person@example.test", "subject",
                "body", "<id@example.test>");

        org.mockito.Mockito.verify(messageMapper).insertWithSequence(any());
        // 没有联系人就没有话题可记 —— 但发送本身照常落库。
        org.mockito.Mockito.verifyNoInteractions(topicActivityRecorder);
    }

    @Test
    void persistedOutboundEmailUsesConversationIngestSequence() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(java.util.UUID.randomUUID());
        account.setChannelType("email");
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(java.util.UUID.randomUUID());
        identity.setContactId(java.util.UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(java.util.UUID.randomUUID());
        when(channelAccountMapper.findByIdAndOwner(account.getId(), ownerId)).thenReturn(account);
        when(contactIdentityMapper.findSendableEmailRecipient(account.getId(), "customer@example.test"))
                .thenReturn(java.util.Optional.of(identity));
        when(conversationMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(conversation));
        when(messageMapper.insertWithSequence(any())).thenReturn(1);

        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper);
        service.persistOutbound(ownerId, account.getId(), "customer@example.test", "subject", "body", "<message@example.test>");

        verify(messageMapper).insertWithSequence(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldConstructWithDependencies() {
        when(config.smtpHost()).thenReturn("smtp.example.com");
        when(config.smtpUser()).thenReturn("user@example.com");
        when(config.smtpPassword()).thenReturn("pass");
        when(config.smtpPort()).thenReturn("465");
        when(config.smtpSsl()).thenReturn(true);
        when(config.smtpStartTls()).thenReturn(false);
        when(config.smtpResolveIpv4()).thenReturn(false);
        when(config.mailFrom()).thenReturn("user@example.com");
        when(config.mailFromName()).thenReturn("");
        when(config.smtpLocalhost()).thenReturn(null);

        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper);
        assertNotNull(service);
    }

    @Test
    void missingToIsNotAnExistingRecipient() {
        configureOutboundAccount();
        when(config.smtpHost()).thenReturn("smtp.example.com");
        when(config.smtpUser()).thenReturn("user@example.com");
        when(config.smtpPassword()).thenReturn("pass");
        when(config.smtpPort()).thenReturn("465");
        when(config.smtpSsl()).thenReturn(true);
        when(config.smtpStartTls()).thenReturn(false);
        when(config.smtpResolveIpv4()).thenReturn(false);
        when(config.mailFrom()).thenReturn("user@example.com");
        when(config.mailFromName()).thenReturn("");
        when(config.smtpLocalhost()).thenReturn(null);

        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.send(ownerId, null, "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code()).isEqualTo("EMAIL_RECIPIENT_NOT_FOUND");
    }

    @Test
    void smtpSuccessWithMessageStatusFailureReturnsUnknownAndPersistsVisibleSubmission() throws Exception {
        configureSmtp();
        configureOutboundAccount();
        when(submissionMapper.insert(any(EmailSubmissionEntity.class))).thenReturn(1);
        java.util.concurrent.atomic.AtomicBoolean leaseClosed = new java.util.concurrent.atomic.AtomicBoolean();
        when(leaseKeeper.track(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(() -> leaseClosed.set(true));
        java.util.List<String> statusHistory = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            statusHistory.add(invocation.<EmailSubmissionEntity>getArgument(0).getStatus());
            return 1;
        }).when(submissionMapper).update(any(EmailSubmissionEntity.class));
        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, null, null, submissionMapper,
                (session, host, port, user, password, message) -> {}, leaseKeeper);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.send(ownerId, "person@example.test", "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SEND_OUTCOME_UNKNOWN");
        verify(submissionMapper, org.mockito.Mockito.times(2)).update(any(EmailSubmissionEntity.class));
        org.assertj.core.api.Assertions.assertThat(statusHistory).containsExactly("SMTP_SENT", "UNKNOWN");
        org.assertj.core.api.Assertions.assertThat(leaseClosed).isTrue();
        org.mockito.ArgumentCaptor<EmailSubmissionEntity> submissionCaptor =
                org.mockito.ArgumentCaptor.forClass(EmailSubmissionEntity.class);
        verify(submissionMapper).insert(submissionCaptor.capture());
        verify(leaseKeeper).track(eq(submissionCaptor.getValue().getId()),
                eq(submissionCaptor.getValue().getLeaseToken()));
    }

    @Test
    void smtpSuccessWithLocalAttachmentFailureDoesNotLeaveSubmissionAsSent() throws Exception {
        configureSmtp();
        configureOutboundAccount();
        when(submissionMapper.insert(any(EmailSubmissionEntity.class))).thenReturn(1);
        when(submissionMapper.update(any(EmailSubmissionEntity.class))).thenReturn(1);
        EmailAttachmentStore attachmentStore = org.mockito.Mockito.mock(EmailAttachmentStore.class);
        org.mockito.Mockito.doThrow(new RuntimeException("attachment persistence failed"))
                .when(attachmentStore).store(any(), any(), eq(true));
        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                attachmentStore, null, null, submissionMapper,
                (session, host, port, user, password, message) -> {});

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.send(
                        ownerId, "person@example.test", "subject", "body",
                        java.util.List.of(new EmailAttachmentInput("file.txt", "text/plain", 1,
                                () -> new java.io.ByteArrayInputStream(new byte[]{1})))))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SUBMISSION_PERSIST_FAILED");
        org.mockito.ArgumentCaptor<EmailSubmissionEntity> row =
                org.mockito.ArgumentCaptor.forClass(EmailSubmissionEntity.class);
        verify(submissionMapper, org.mockito.Mockito.atLeastOnce()).update(row.capture());
        org.assertj.core.api.Assertions.assertThat(row.getAllValues())
                .extracting(EmailSubmissionEntity::getStatus)
                .contains("FAILED")
                .doesNotContain("SENT");
    }

    @Test
    void smtpTransportFailurePersistsUnknownWithoutBlindRetry() {
        configureSmtp();
        configureOutboundAccount();
        when(submissionMapper.insert(any(EmailSubmissionEntity.class))).thenReturn(1);
        when(submissionMapper.update(any(EmailSubmissionEntity.class))).thenReturn(1);
        java.util.List<String> statusHistory = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            statusHistory.add(invocation.<EmailSubmissionEntity>getArgument(0).getStatus());
            return 1;
        }).when(submissionMapper).update(any(EmailSubmissionEntity.class));
        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, null, null, submissionMapper,
                (session, host, port, user, password, message) -> {
                    throw new java.io.IOException("connection closed during SMTP submission");
                });

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.send(ownerId, "person@example.test", "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SEND_OUTCOME_UNKNOWN");
        org.assertj.core.api.Assertions.assertThat(statusHistory).containsExactly("UNKNOWN");
    }

    @Test
    void submissionRecordMustExistBeforeSmtpCanSend() {
        configureSmtp();
        configureOutboundAccount();
        when(submissionMapper.insert(any(EmailSubmissionEntity.class))).thenReturn(0);
        java.util.concurrent.atomic.AtomicBoolean smtpCalled = new java.util.concurrent.atomic.AtomicBoolean();
        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, null, null, submissionMapper,
                (session, host, port, user, password, message) -> smtpCalled.set(true));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.send(ownerId, "person@example.test", "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SUBMISSION_PERSIST_FAILED");
        org.assertj.core.api.Assertions.assertThat(smtpCalled).isFalse();
    }

    @Test
    void messagePersistenceFailureMustNotCallSmtp() {
        configureSmtp();
        configureOutboundAccount();
        when(submissionMapper.insert(any(EmailSubmissionEntity.class))).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("message insert failed"))
                .when(messageMapper).insertWithSequence(any());
        java.util.concurrent.atomic.AtomicBoolean smtpCalled = new java.util.concurrent.atomic.AtomicBoolean();
        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, null, null, submissionMapper,
                (session, host, port, user, password, message) -> smtpCalled.set(true));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.send(ownerId, "person@example.test", "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SUBMISSION_PERSIST_FAILED");
        org.assertj.core.api.Assertions.assertThat(smtpCalled).isFalse();
    }

    @Test
    void orphanIdentityCreationFailureMustNotPersistOrCallSmtp() {
        configureSmtp();
        configureOutboundAccount();
        // 复用查询取不到，且新建孤立身份后重查仍取不到 —— 落库前就该失败，不得碰 SMTP。
        when(contactIdentityMapper.findSendableEmailRecipient(any(), eq("stranger@example.test")))
                .thenReturn(java.util.Optional.empty());
        when(contactIdentityMapper.findByNormalizedValueInScope(eq("email"), any(), eq("stranger@example.test")))
                .thenReturn(java.util.Optional.empty());
        when(submissionMapper.insert(any(EmailSubmissionEntity.class))).thenReturn(1);
        java.util.concurrent.atomic.AtomicBoolean smtpCalled = new java.util.concurrent.atomic.AtomicBoolean();
        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, null, null, submissionMapper,
                (session, host, port, user, password, message) -> smtpCalled.set(true));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.send(ownerId, "stranger@example.test", "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SUBMISSION_PERSIST_FAILED");
        org.assertj.core.api.Assertions.assertThat(smtpCalled).isFalse();
        org.mockito.Mockito.verify(messageMapper, org.mockito.Mockito.never()).insertWithSequence(any());
        org.mockito.Mockito.verifyNoInteractions(submissionMapper);
    }

    @Test
    void transactionCommitFailureMustNotCallSmtp() {
        configureSmtp();
        configureOutboundAccount();
        when(submissionMapper.insert(any(EmailSubmissionEntity.class))).thenReturn(1);
        org.springframework.transaction.PlatformTransactionManager transactions =
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        org.mockito.Mockito.doThrow(new IllegalStateException("commit failed"))
                .when(transactions).commit(any());
        java.util.concurrent.atomic.AtomicBoolean smtpCalled = new java.util.concurrent.atomic.AtomicBoolean();
        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, null, null, submissionMapper,
                (session, host, port, user, password, message) -> smtpCalled.set(true), null,
                new org.springframework.transaction.support.TransactionTemplate(transactions));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.send(ownerId, "person@example.test", "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SUBMISSION_PERSIST_FAILED");
        org.mockito.Mockito.verify(transactions).commit(any());
        org.assertj.core.api.Assertions.assertThat(smtpCalled).isFalse();
    }

    @Test
    void ownedSendRejectsAmbiguousEmailAccountsBeforeSmtp() {
        configureSmtp();
        ChannelAccountEntity first = new ChannelAccountEntity();
        first.setId(java.util.UUID.randomUUID());
        ChannelAccountEntity second = new ChannelAccountEntity();
        second.setId(java.util.UUID.randomUUID());
        when(channelAccountMapper.findByOwnerAndChannelType(ownerId, "email"))
                .thenReturn(java.util.List.of(first, second));
        java.util.concurrent.atomic.AtomicBoolean smtpCalled = new java.util.concurrent.atomic.AtomicBoolean();
        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, null, null, submissionMapper,
                (session, host, port, user, password, message) -> smtpCalled.set(true));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.send(ownerId, "person@example.test", "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("CHANNEL_ACCOUNT_REQUIRED");
        org.assertj.core.api.Assertions.assertThat(smtpCalled).isFalse();
    }

    @Test
    void attachmentPersistenceFailureMustNotCallSmtp() throws Exception {
        configureSmtp();
        configureOutboundAccount();
        when(submissionMapper.insert(any(EmailSubmissionEntity.class))).thenReturn(1);
        when(messageMapper.insertWithSequence(any())).thenReturn(1);
        when(submissionMapper.update(any(EmailSubmissionEntity.class))).thenReturn(1);
        EmailAttachmentStore attachmentStore = org.mockito.Mockito.mock(EmailAttachmentStore.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("attachment store failed"))
                .when(attachmentStore).store(any(), any(), eq(true));
        java.util.concurrent.atomic.AtomicBoolean smtpCalled = new java.util.concurrent.atomic.AtomicBoolean();
        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                attachmentStore, null, null, submissionMapper,
                (session, host, port, user, password, message) -> smtpCalled.set(true));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.send(
                        ownerId, "person@example.test", "subject", "body",
                        java.util.List.of(new EmailAttachmentInput("file.txt", "text/plain", 1,
                                () -> new java.io.ByteArrayInputStream(new byte[]{1})))))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SUBMISSION_PERSIST_FAILED");
        org.assertj.core.api.Assertions.assertThat(smtpCalled).isFalse();
    }

    @Test
    void successfulEmailPersistsFullMessageAndAttachmentsBeforeSmtp() throws Exception {
        configureSmtp();
        configureOutboundAccount();
        java.util.List<String> steps = new java.util.ArrayList<>();
        when(submissionMapper.insert(any(EmailSubmissionEntity.class))).thenAnswer(call -> {
            steps.add("submission");
            return 1;
        });
        org.mockito.ArgumentCaptor<com.crmforlogistics.messagecenter.entity.MessageEntity> messageRow =
                org.mockito.ArgumentCaptor.forClass(com.crmforlogistics.messagecenter.entity.MessageEntity.class);
        when(messageMapper.insertWithSequence(messageRow.capture())).thenAnswer(call -> {
            steps.add("message");
            return 1;
        });
        EmailAttachmentStore attachmentStore = org.mockito.Mockito.mock(EmailAttachmentStore.class);
        when(attachmentStore.store(any(), any(), eq(true))).thenAnswer(call -> {
            steps.add("attachment");
            return java.util.List.of();
        });
        when(submissionMapper.update(any(EmailSubmissionEntity.class))).thenAnswer(call -> {
            steps.add(call.<EmailSubmissionEntity>getArgument(0).getStatus());
            return 1;
        });
        when(messageMapper.updateDeliveryStatus(any(), any(), any(), any())).thenAnswer(call -> {
            steps.add("message_" + call.getArgument(2));
            return 1;
        });
        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                attachmentStore, null, null, submissionMapper,
                (session, host, port, user, password, mime) -> {
                    steps.add("smtp");
                    org.assertj.core.api.Assertions.assertThat(messageRow.getValue().getProviderMessageId())
                            .isEqualTo(mime.getMessageID());
                });

        EmailSendService.SendResult result = service.send(ownerId, "person@example.test", "subject", "body",
                java.util.List.of(new EmailAttachmentInput("file.txt", "text/plain", 1,
                        () -> new java.io.ByteArrayInputStream(new byte[]{1}))));

        org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("sent");
        org.assertj.core.api.Assertions.assertThat(messageRow.getValue().getCurrentStatus()).isEqualTo("pending");
        org.assertj.core.api.Assertions.assertThat(messageRow.getValue().getCountsAsUnread()).isFalse();
        org.assertj.core.api.Assertions.assertThat(messageRow.getValue().getCurrentStatusAt()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(steps)
                .containsExactly("submission", "message", "attachment", "smtp", "SMTP_SENT", "message_sent", "SENT");
    }

    @Test
    void finalSubmissionUpdateFailureDoesNotResendEmail() throws Exception {
        configureSmtp();
        configureOutboundAccount();
        when(submissionMapper.insert(any(EmailSubmissionEntity.class))).thenReturn(1);
        java.util.List<String> statuses = new java.util.ArrayList<>();
        when(submissionMapper.update(any(EmailSubmissionEntity.class))).thenAnswer(call -> {
            String status = call.<EmailSubmissionEntity>getArgument(0).getStatus();
            statuses.add(status);
            return "SENT".equals(status) ? 0 : 1;
        });
        when(messageMapper.updateDeliveryStatus(any(), any(), eq("sent"), any())).thenReturn(1);
        java.util.concurrent.atomic.AtomicInteger smtpCalls = new java.util.concurrent.atomic.AtomicInteger();
        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, null, null, submissionMapper,
                (session, host, port, user, password, message) -> smtpCalls.incrementAndGet());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.send(ownerId, "person@example.test", "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SEND_OUTCOME_UNKNOWN");
        org.assertj.core.api.Assertions.assertThat(statuses).containsExactly("SMTP_SENT", "SENT", "UNKNOWN");
        org.assertj.core.api.Assertions.assertThat(smtpCalls).hasValue(1);
        org.mockito.Mockito.verify(messageMapper, org.mockito.Mockito.never())
                .markSubmissionUnknownIfUnresolved(any(), any());
    }

    private void configureOutboundAccount() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(java.util.UUID.randomUUID());
        account.setOwnerUserId(ownerId);
        account.setChannelType("email");
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(java.util.UUID.randomUUID());
        identity.setContactId(java.util.UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(java.util.UUID.randomUUID());
        when(channelAccountMapper.findByOwnerAndChannelType(ownerId, "email"))
                .thenReturn(java.util.List.of(account));
        when(contactIdentityMapper.findSendableEmailRecipient(account.getId(), "person@example.test"))
                .thenReturn(java.util.Optional.of(identity));
        when(conversationMapper.selectList(any())).thenReturn(java.util.List.of(conversation));
        when(messageMapper.insertWithSequence(any())).thenReturn(1);
    }

    private void configureSmtp() {
        when(config.smtpHost()).thenReturn("smtp.example.com");
        when(config.smtpUser()).thenReturn("user@example.com");
        when(config.smtpPassword()).thenReturn("pass");
        when(config.smtpPort()).thenReturn("465");
        when(config.smtpSsl()).thenReturn(true);
        when(config.smtpStartTls()).thenReturn(false);
        when(config.smtpResolveIpv4()).thenReturn(false);
        when(config.mailFrom()).thenReturn("user@example.com");
        when(config.mailFromName()).thenReturn("");
        when(config.smtpLocalhost()).thenReturn(null);
    }
}
