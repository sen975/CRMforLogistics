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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailSendServiceTest {

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
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(java.util.UUID.randomUUID());
        identity.setContactId(java.util.UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(java.util.UUID.randomUUID());
        when(channelAccountMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(account));
        when(contactIdentityMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(identity));
        when(conversationMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(conversation));

        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, topicActivityRecorder);
        service.persistOutbound("customer@example.test", "subject", "body", "<message@example.test>");

        verify(topicActivityRecorder).recordContact(eq(identity.getContactId()),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void persistedOutboundEmailUsesConversationIngestSequence() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(java.util.UUID.randomUUID());
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(java.util.UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(java.util.UUID.randomUUID());
        when(channelAccountMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(account));
        when(contactIdentityMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(identity));
        when(conversationMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(conversation));

        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper);
        service.persistOutbound("customer@example.test", "subject", "body", "<message@example.test>");

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
    void shouldValidateRequiredTo() {
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

        assertThrows(IllegalArgumentException.class,
                () -> service.send(null, "subject", "body"));
    }

    @Test
    void smtpSuccessWithLocalMessageFailureReturnsUnknownAndPersistsVisibleSubmission() throws Exception {
        configureSmtp();
        when(channelAccountMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of());
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
                        () -> service.send("person@example.test", "subject", "body"))
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
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(java.util.UUID.randomUUID());
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(java.util.UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(java.util.UUID.randomUUID());
        when(channelAccountMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(account));
        when(contactIdentityMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(identity));
        when(conversationMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(conversation));
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
                        "person@example.test", "subject", "body",
                        java.util.List.of(new EmailAttachmentInput("file.txt", "text/plain", 1,
                                () -> new java.io.ByteArrayInputStream(new byte[]{1})))))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SEND_OUTCOME_UNKNOWN");
        org.mockito.ArgumentCaptor<EmailSubmissionEntity> row =
                org.mockito.ArgumentCaptor.forClass(EmailSubmissionEntity.class);
        verify(submissionMapper, org.mockito.Mockito.atLeastOnce()).update(row.capture());
        org.assertj.core.api.Assertions.assertThat(row.getAllValues())
                .extracting(EmailSubmissionEntity::getStatus)
                .contains("UNKNOWN")
                .doesNotContain("SENT");
    }

    @Test
    void smtpTransportFailurePersistsUnknownWithoutBlindRetry() {
        configureSmtp();
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
                        () -> service.send("person@example.test", "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SEND_OUTCOME_UNKNOWN");
        org.assertj.core.api.Assertions.assertThat(statusHistory).containsExactly("UNKNOWN");
    }

    @Test
    void submissionRecordMustExistBeforeSmtpCanSend() {
        configureSmtp();
        when(submissionMapper.insert(any(EmailSubmissionEntity.class))).thenReturn(0);
        java.util.concurrent.atomic.AtomicBoolean smtpCalled = new java.util.concurrent.atomic.AtomicBoolean();
        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, null, null, submissionMapper,
                (session, host, port, user, password, message) -> smtpCalled.set(true));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.send("person@example.test", "subject", "body"))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_SUBMISSION_PERSIST_FAILED");
        org.assertj.core.api.Assertions.assertThat(smtpCalled).isFalse();
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
