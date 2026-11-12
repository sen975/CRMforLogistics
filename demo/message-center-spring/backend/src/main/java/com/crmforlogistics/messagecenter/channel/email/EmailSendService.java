package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.EmailSubmissionMapper;
import com.crmforlogistics.messagecenter.entity.EmailSubmissionEntity;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class EmailSendService {

    private static final Logger log = LoggerFactory.getLogger(EmailSendService.class);

    private final AppConfig config;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final ChannelAccountMapper channelAccountMapper;
    private final ContactIdentityMapper contactIdentityMapper;
    private final EmailAttachmentReader attachmentReader;
    private final EmailAttachmentStore attachmentStore;
    private final AiTopicActivityRecorder topicActivityRecorder;
    private final CredentialCipher credentialCipher;
    private final EmailSubmissionMapper submissionMapper;
    private final SmtpSender smtpSender;
    private final EmailSubmissionLeaseKeeper leaseKeeper;
    private final TransactionTemplate transactionTemplate;

    @FunctionalInterface
    interface SmtpSender {
        void send(Session session, String host, int port, String username, String password,
                  MimeMessage message) throws Exception;
    }

    public EmailSendService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper,
                            ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper) {
        this(config, messageMapper, conversationMapper, channelAccountMapper, contactIdentityMapper,
                null, null, null, null, EmailSendService::sendSmtp, null);
    }

    public EmailSendService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper,
                            ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper,
                            EmailAttachmentStore attachmentStore) {
        this(config, messageMapper, conversationMapper, channelAccountMapper,
                contactIdentityMapper, attachmentStore, null, null);
    }

    public EmailSendService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper,
                            ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper,
                            EmailAttachmentStore attachmentStore,
                            AiTopicActivityRecorder topicActivityRecorder) {
        this(config, messageMapper, conversationMapper, channelAccountMapper, contactIdentityMapper,
                attachmentStore, topicActivityRecorder, null);
    }

    public EmailSendService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper,
                            ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper,
                            EmailAttachmentStore attachmentStore,
                            AiTopicActivityRecorder topicActivityRecorder,
                            CredentialCipher credentialCipher,
                            EmailSubmissionMapper submissionMapper) {
        this(config, messageMapper, conversationMapper, channelAccountMapper, contactIdentityMapper,
                attachmentStore, topicActivityRecorder, credentialCipher, submissionMapper,
                EmailSendService::sendSmtp, null);
    }

    @Autowired
    public EmailSendService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper,
                            ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper,
                            EmailAttachmentStore attachmentStore,
                            AiTopicActivityRecorder topicActivityRecorder,
                            CredentialCipher credentialCipher,
                            EmailSubmissionMapper submissionMapper,
                            EmailSubmissionLeaseKeeper leaseKeeper,
                            PlatformTransactionManager transactionManager) {
        this(config, messageMapper, conversationMapper, channelAccountMapper, contactIdentityMapper,
                attachmentStore, topicActivityRecorder, credentialCipher, submissionMapper,
                EmailSendService::sendSmtp, leaseKeeper, new TransactionTemplate(transactionManager));
    }

    EmailSendService(AppConfig config, MessageMapper messageMapper,
                     ConversationMapper conversationMapper,
                     ChannelAccountMapper channelAccountMapper,
                     ContactIdentityMapper contactIdentityMapper,
                     EmailAttachmentStore attachmentStore,
                     AiTopicActivityRecorder topicActivityRecorder,
                     CredentialCipher credentialCipher,
                     EmailSubmissionMapper submissionMapper,
                     SmtpSender smtpSender) {
        this(config, messageMapper, conversationMapper, channelAccountMapper, contactIdentityMapper,
                attachmentStore, topicActivityRecorder, credentialCipher, submissionMapper, smtpSender, null);
    }

    EmailSendService(AppConfig config, MessageMapper messageMapper,
                     ConversationMapper conversationMapper,
                     ChannelAccountMapper channelAccountMapper,
                     ContactIdentityMapper contactIdentityMapper,
                     EmailAttachmentStore attachmentStore,
                     AiTopicActivityRecorder topicActivityRecorder,
                     CredentialCipher credentialCipher,
                     EmailSubmissionMapper submissionMapper,
                     SmtpSender smtpSender,
                     EmailSubmissionLeaseKeeper leaseKeeper) {
        this(config, messageMapper, conversationMapper, channelAccountMapper, contactIdentityMapper,
                attachmentStore, topicActivityRecorder, credentialCipher, submissionMapper,
                smtpSender, leaseKeeper, null);
    }

    EmailSendService(AppConfig config, MessageMapper messageMapper,
                     ConversationMapper conversationMapper,
                     ChannelAccountMapper channelAccountMapper,
                     ContactIdentityMapper contactIdentityMapper,
                     EmailAttachmentStore attachmentStore,
                     AiTopicActivityRecorder topicActivityRecorder,
                     CredentialCipher credentialCipher,
                     EmailSubmissionMapper submissionMapper,
                     SmtpSender smtpSender,
                     EmailSubmissionLeaseKeeper leaseKeeper,
                     TransactionTemplate transactionTemplate) {
        this.config = config;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.channelAccountMapper = channelAccountMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.attachmentReader = new EmailAttachmentReader(
                positiveIntOrDefault(config.emailAttachmentMaxCount(), 16),
                positiveOrDefault(config.emailAttachmentMaxTotalBytes(), 20_971_520L));
        this.attachmentStore = attachmentStore;
        this.topicActivityRecorder = topicActivityRecorder;
        this.credentialCipher = credentialCipher;
        this.submissionMapper = submissionMapper;
        this.smtpSender = smtpSender;
        this.leaseKeeper = leaseKeeper;
        this.transactionTemplate = transactionTemplate;
    }

    public EmailSendService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper,
                            ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper,
                            EmailAttachmentStore attachmentStore,
                            AiTopicActivityRecorder topicActivityRecorder,
                            CredentialCipher credentialCipher) {
        this(config, messageMapper, conversationMapper, channelAccountMapper, contactIdentityMapper,
                attachmentStore, topicActivityRecorder, credentialCipher, null);
    }

    public record SendResult(String messageId, String from, String to, String subject, String status) {}
    public record SubmissionOutcome(UUID id, String providerMessageId, String recipient,
                                    String subject, String status, Instant updatedAt) {}

    public List<SubmissionOutcome> listUnknownSubmissions(UUID ownerId, int limit) {
        if (ownerId == null) throw new EmailException("AUTHENTICATION_REQUIRED", "Authentication is required");
        if (submissionMapper == null) return List.of();
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        submissionMapper.markStaleSubmissionsUnknown(ownerId, 100);
        return submissionMapper.listUnknownByOwner(ownerId, boundedLimit).stream()
                .map(row -> new SubmissionOutcome(row.getId(), row.getProviderMessageId(),
                        row.getRecipient(), row.getSubject(), row.getStatus(), row.getUpdatedAt()))
                .toList();
    }

    public SendResult send(String to, String subject, String body) throws Exception {
        throw new EmailException("AUTHENTICATION_REQUIRED", "Authentication is required");
    }

    public SendResult send(String to, String subject, String body, List<EmailAttachmentInput> attachmentInputs) throws Exception {
        throw new EmailException("AUTHENTICATION_REQUIRED", "Authentication is required");
    }

    public SendResult send(UUID ownerId, String to, String subject, String body) throws Exception {
        return send(ownerId, to, subject, body, List.of());
    }

    public SendResult send(UUID ownerId, String to, String subject, String body,
                           List<EmailAttachmentInput> attachmentInputs) throws Exception {
        ChannelAccountEntity account = requireOwnedEmailAccount(ownerId);
        String normalizedEmail = recipientEmail(to);
        ContactIdentityEntity identity = resolveRecipient(account.getId(), normalizedEmail);
        Map<String, String> saved = decrypt(account);
        return sendInternal(ownerId, account, identity, saved, normalizedEmail, subject, body, attachmentInputs);
    }

    private SendResult sendInternal(UUID ownerId, ChannelAccountEntity account, ContactIdentityEntity identity,
                                    Map<String, String> saved,
                                    String to, String subject, String body,
                                    List<EmailAttachmentInput> attachmentInputs) throws Exception {
        String cleanTo = required(to, "to");
        var attachments = attachmentReader.read(attachmentInputs == null ? List.of() : attachmentInputs);
        SmtpSettings settings = SmtpSettings.from(config, saved);
        SmtpEndpoint endpoint = smtpEndpoint(settings);
        Session session = Session.getInstance(smtpProperties(endpoint, settings));
        MimeMessage message = new MimeMessage(session);
        try {
            String fromName = settings.fromName();
            if (fromName == null || fromName.isBlank()) {
                message.setFrom(new InternetAddress(settings.from()));
            } else {
                message.setFrom(new InternetAddress(settings.from(), fromName, "UTF-8"));
            }
        } catch (UnsupportedEncodingException ex) {
            throw new MessagingException("Failed to encode sender name", ex);
        }
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(cleanTo));
        message.setSubject(subject == null ? "" : subject, "UTF-8");
        if (attachments.isEmpty()) {
            message.setText(body == null ? "" : body, "UTF-8");
        } else {
            MimeMultipart multipart = new MimeMultipart("mixed");
            MimeBodyPart bodyPart = new MimeBodyPart();
            bodyPart.setText(body == null ? "" : body, "UTF-8");
            multipart.addBodyPart(bodyPart);
            for (EmailAttachmentPayload attachment : attachments) {
                MimeBodyPart part = new MimeBodyPart();
                part.setContent(attachment.bytes(), attachment.mimeType());
                part.setFileName(jakarta.mail.internet.MimeUtility.encodeText(attachment.fileName(), "UTF-8", null));
                part.setDisposition(jakarta.mail.Part.ATTACHMENT);
                multipart.addBodyPart(part);
            }
            message.setContent(multipart);
        }
        message.saveChanges();
        message.setHeader("Message-ID", messageIdHeader(settings.from(), endpoint.serverName()));
        String providerMessageId = message.getMessageID();
        PreparedSend prepared = prepareOutbound(ownerId, account, identity, cleanTo, subject, body,
                providerMessageId, attachments);
        EmailSubmissionEntity submission = prepared.submission();
        EmailSubmissionLeaseKeeper.Registration leaseRegistration = submission == null || leaseKeeper == null
                ? () -> { }
                : leaseKeeper.track(submission.getId(), submission.getLeaseToken());

        try (leaseRegistration) {
            try {
                smtpSender.send(session, endpoint.connectHost(), settings.port(),
                        settings.user(), settings.password(), message);
            } catch (Exception e) {
                markSubmissionUnknown(submission, providerMessageId, "SMTP submission result is unknown");
                markMessageUnknown(prepared.messageId());
                throw new EmailException("EMAIL_SEND_OUTCOME_UNKNOWN",
                        "SMTP submission result is unknown; do not retry until delivery is checked", e);
            }

            if (!updateSubmission(submission, providerMessageId, "SMTP_SENT", null)) {
                markSubmissionUnknown(submission, providerMessageId, "SMTP succeeded but submission state could not be recorded");
                markMessageUnknown(prepared.messageId());
                return unknownResult(providerMessageId);
            }

            try {
                if (messageMapper.updateDeliveryStatus(prepared.messageId(), providerMessageId,
                        "sent", Instant.now()) != 1) {
                    throw new IllegalStateException("Email message status update did not affect one row");
                }
            } catch (Exception e) {
                log.error("event=email_message_sent_state_persist_failed messageId={}", prepared.messageId(), e);
                markSubmissionUnknown(submission, providerMessageId, "SMTP succeeded but message state could not be recorded");
                markMessageUnknown(prepared.messageId());
                return unknownResult(providerMessageId);
            }
            if (!updateSubmission(submission, providerMessageId, "SENT", null)) {
                markSubmissionUnknown(submission, providerMessageId, "SMTP succeeded but final submission state could not be recorded");
                return unknownResult(providerMessageId);
            }
            return new SendResult(prepared.messageId().toString(), settings.from(), cleanTo, subject, "sent");
        }
    }

    private record PreparedSend(UUID messageId, EmailSubmissionEntity submission) {}

    private PreparedSend prepareOutbound(UUID ownerId, ChannelAccountEntity account, ContactIdentityEntity identity,
                                         String to, String subject, String body, String providerMessageId,
                                         List<EmailAttachmentPayload> attachments) {
        PreparedSend prepared;
        try {
            prepared = transactionTemplate == null
                    ? persistBeforeSend(ownerId, account, identity, to, subject, body, providerMessageId)
                    : transactionTemplate.execute(status -> persistBeforeSend(
                            ownerId, account, identity, to, subject, body, providerMessageId));
            if (prepared == null) {
                throw new IllegalStateException("Email persistence transaction returned no message");
            }
        } catch (Exception e) {
            if (e instanceof EmailException emailException) throw emailException;
            throw new EmailException("EMAIL_SUBMISSION_PERSIST_FAILED", "Unable to persist email before sending", e);
        }
        if (!attachments.isEmpty()) {
            try {
                if (attachmentStore == null) {
                    throw new IllegalStateException("Email attachment store is unavailable");
                }
                attachmentStore.store(prepared.messageId(), attachments, true);
            } catch (Exception e) {
                markUnsentFailed(prepared, providerMessageId);
                throw new EmailException("EMAIL_SUBMISSION_PERSIST_FAILED",
                        "Unable to persist email attachments before sending", e);
            }
        }
        return prepared;
    }

    private PreparedSend persistBeforeSend(UUID ownerId, ChannelAccountEntity account, ContactIdentityEntity identity,
                                           String to, String subject, String body, String providerMessageId) {
        EmailSubmissionEntity submission = beginSubmission(ownerId, account, to, subject);
        UUID messageId = persistOutboundForAccount(account, identity, subject, body, providerMessageId);
        if (messageId == null) {
            throw new EmailException("EMAIL_SUBMISSION_PERSIST_FAILED", "Unable to persist email message before sending");
        }
        return new PreparedSend(messageId, submission);
    }

    private void markUnsentFailed(PreparedSend prepared, String providerMessageId) {
        if (!updateSubmission(prepared.submission(), providerMessageId,
                "FAILED", "Email attachment persistence failed before SMTP submission")) {
            log.error("event=email_unsent_submission_state_persist_failed submissionId={}",
                    prepared.submission() == null ? null : prepared.submission().getId());
        }
        try {
            if (messageMapper.updateDeliveryStatus(prepared.messageId(), providerMessageId,
                    "failed", Instant.now()) != 1) {
                log.error("event=email_unsent_message_state_persist_failed messageId={}", prepared.messageId());
            }
        } catch (Exception e) {
            log.error("event=email_unsent_message_state_persist_failed messageId={}", prepared.messageId(), e);
        }
    }

    private void markMessageUnknown(UUID messageId) {
        try {
            if (messageMapper.markSubmissionUnknownIfUnresolved(messageId, Instant.now()) != 1) {
                log.warn("event=email_message_unknown_state_not_applied messageId={}", messageId);
            }
        } catch (Exception e) {
            log.error("event=email_message_unknown_state_persist_failed messageId={}", messageId, e);
        }
    }

    private boolean updateSubmission(EmailSubmissionEntity submission, String providerMessageId,
                                     String status, String lastError) {
        if (submission == null) return true;
        submission.setProviderMessageId(providerMessageId);
        submission.setStatus(status);
        submission.setLastError(lastError);
        try {
            return submissionMapper.update(submission) == 1;
        } catch (Exception e) {
            log.error("event=email_submission_state_persist_failed submissionId={} targetStatus={}",
                    submission.getId(), status, e);
            return false;
        }
    }

    private void markSubmissionUnknown(EmailSubmissionEntity submission, String messageId, String reason) {
        if (!updateSubmission(submission, messageId, "UNKNOWN", reason)) {
            log.error("event=email_submission_unknown_state_unpersisted submissionId={}",
                    submission == null ? null : submission.getId());
        }
    }

    private SendResult unknownResult(String messageId) {
        log.error("event=email_send_outcome_unknown messageId={}", messageId);
        throw new EmailException("EMAIL_SEND_OUTCOME_UNKNOWN",
                "SMTP accepted the message but local delivery state is incomplete; check delivery before retrying");
    }

    private static void sendSmtp(Session session, String host, int port, String username,
                                 String password, MimeMessage message) throws Exception {
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(host, port, username, password);
            transport.sendMessage(message, message.getAllRecipients());
        }
    }

    private EmailSubmissionEntity beginSubmission(UUID ownerId, ChannelAccountEntity account,
                                                  String to, String subject) {
        if (submissionMapper == null) return null;
        EmailSubmissionEntity row = new EmailSubmissionEntity();
        row.setOwnerUserId(ownerId);
        row.setId(UUID.randomUUID()); row.setChannelAccountId(account == null ? null : account.getId());
        row.setLeaseToken(UUID.randomUUID());
        row.setRecipient(to); row.setSubject(subject == null ? "" : subject); row.setStatus("PENDING");
        try {
            if (submissionMapper.insert(row) != 1) {
                throw new IllegalStateException("Email submission insert did not affect one row");
            }
            return row;
        }
        catch (Exception e) {
            throw new EmailException("EMAIL_SUBMISSION_PERSIST_FAILED",
                    "Unable to create email submission", e);
        }
    }

    UUID persistOutbound(String to, String subject, String body, String messageId) {
        throw new EmailException("AUTHENTICATION_REQUIRED", "Authentication is required");
    }

    UUID persistOutbound(UUID ownerId, UUID accountId, String to, String subject,
                         String body, String messageId) {
        requireOwner(ownerId);
        ChannelAccountEntity account = accountId == null ? null
                : channelAccountMapper.findByIdAndOwner(accountId, ownerId);
        if (account == null || !"email".equals(account.getChannelType())) {
            throw new EmailException("CHANNEL_ACCOUNT_REQUIRED", "An owned email channel account is required");
        }
        ContactIdentityEntity identity = resolveRecipient(accountId, recipientEmail(to));
        return persistOutboundForAccount(account, identity, subject, body, messageId);
    }

    private UUID persistOutboundForAccount(ChannelAccountEntity account, ContactIdentityEntity identity,
                                            String subject, String body, String messageId) {
        try {
            if (account == null) {
                log.warn("No email channel account found, skipping DB persist");
                return null;
            }
            ConversationEntity conversation = resolveOrCreateConversation(identity.getId(), account.getId());
            MessageEntity entity = new MessageEntity();
            entity.setId(UUID.randomUUID());
            entity.setChannelAccountId(account.getId());
            entity.setConversationId(conversation.getId());
            entity.setDirection("outbound");
            entity.setMessageKind("email");
            entity.setSubject(subject);
            entity.setBodyText(body);
            entity.setOccurredAt(Instant.now());
            entity.setProviderMessageId(messageId);
            entity.setCountsAsUnread(false);
            entity.setCurrentStatus("pending");
            entity.setCurrentStatusAt(entity.getOccurredAt());
            if (messageMapper.insertWithSequence(entity) != 1) {
                throw new IllegalStateException("Email message insert did not affect one row");
            }
            if (topicActivityRecorder != null && identity.getContactId() != null) {
                topicActivityRecorder.recordContact(identity.getContactId(), entity.getOccurredAt());
            }
            return entity.getId();
        } catch (Exception e) {
            log.error("Failed to persist outbound email to DB", e);
            return null;
        }
    }

    /**
     * 解析收件身份。<b>刻意不校验收件人归属</b>：发送侧的契约是「只要有一个邮箱地址就能发」。
     *
     * <p>先尽量复用已有身份，这样消息能挂到已存在的会话上；没有就新建一条<b>孤立身份</b>
     * （{@code contact_id} 为空）。刻意<b>不</b>顺手创建联系人 —— 发一封信不该在通讯录里
     * 凭空多出一个人。这个地址将来真被同步或手工加为联系人时，{@code EmailSyncService}
     * 的既有认领逻辑（{@code linkToNewContact}）会把这条孤立身份挂上去，不会重复建档。
     */
    private ContactIdentityEntity resolveRecipient(UUID accountId, String normalizedEmail) {
        ContactIdentityEntity existing = contactIdentityMapper
                .findSendableEmailRecipient(accountId, normalizedEmail)
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        ContactIdentityEntity orphan = new ContactIdentityEntity();
        orphan.setId(UUID.randomUUID());
        orphan.setChannelType("email");
        orphan.setIdentityScope(accountId.toString());
        orphan.setIdentityValue(normalizedEmail);
        orphan.setNormalizedValue(normalizedEmail);
        orphan.setIsPrimary(false);
        orphan.setVerifyStatus("unverified");
        // source 有 CHECK 约束（manual/synced/imported），出站新建只能落在 manual。
        orphan.setSource("manual");
        orphan.setCreatedAt(Instant.now());
        orphan.setUpdatedAt(Instant.now());
        orphan.setVersion(1L);
        // upsert：并发下另一个请求可能刚插过同一条，on conflict do nothing 之后重查即可。
        contactIdentityMapper.insertIfAbsent(orphan);
        return contactIdentityMapper
                .findByNormalizedValueInScope("email", accountId.toString(), normalizedEmail)
                .orElseThrow(() -> new EmailException("EMAIL_SUBMISSION_PERSIST_FAILED",
                        "Unable to persist the email recipient identity"));
    }

    private static String recipientEmail(String to) {
        try {
            InternetAddress[] recipients = InternetAddress.parse(required(to, "to"), false);
            if (recipients.length == 1 && !recipients[0].isGroup()) {
                return recipients[0].getAddress().trim().toLowerCase(Locale.ROOT);
            }
        } catch (jakarta.mail.internet.AddressException | IllegalArgumentException e) {
            throw new EmailException("EMAIL_RECIPIENT_NOT_FOUND",
                    "Email recipient must be a single valid mailbox address", e);
        }
        throw new EmailException("EMAIL_RECIPIENT_NOT_FOUND",
                "Email recipient must be a single mailbox address (recipient lists and groups are not supported)");
    }

    private ConversationEntity resolveOrCreateConversation(UUID identityId, UUID accountId) {
        List<ConversationEntity> existing = conversationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getContactIdentityId, identityId)
                        .eq(ConversationEntity::getChannelAccountId, accountId));
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setContactIdentityId(identityId);
        conversation.setChannelAccountId(accountId);
        conversation.setCreatedAt(Instant.now());
        conversation.setUpdatedAt(Instant.now());
        if (conversationMapper.insert(conversation) != 1) {
            throw new IllegalStateException("Email conversation insert did not affect one row");
        }
        return conversation;
    }

    private Properties smtpProperties(SmtpEndpoint endpoint, SmtpSettings settings) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", endpoint.connectHost());
        props.put("mail.smtp.port", Integer.toString(settings.port()));
        props.put("mail.smtp.ssl.enable", Boolean.toString(settings.ssl()));
        props.put("mail.smtp.starttls.enable", Boolean.toString(settings.startTls()));
        props.put("mail.smtp.from", settings.from());
        String localhost = settings.localhost();
        if (localhost != null && !localhost.isBlank()) {
            props.put("mail.smtp.localhost", localhost);
        }
        if (!endpoint.connectHost().equalsIgnoreCase(endpoint.serverName()) && settings.ssl()) {
            props.put("mail.smtp.ssl.socketFactory", new SniSocketFactory(endpoint.serverName()));
        }
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "30000");
        return props;
    }

    private String mailFrom() {
        String from = config.mailFrom();
        if (from != null && !from.isBlank()) return from;
        String user = config.smtpUser();
        return user != null ? user : "";
    }

    private String smtpPassword() {
        String pw = config.smtpPassword();
        return pw != null ? pw : "";
    }

    private int smtpPort() {
        String port = config.smtpPort();
        return port != null && !port.isBlank() ? Integer.parseInt(port) : 465;
    }

    private SmtpEndpoint smtpEndpoint(SmtpSettings settings) throws Exception {
        String configuredHost = settings.host();
        String serverName = smtpServername(configuredHost, settings.user());
        String connectHost = configuredHost;
        if (settings.resolveIpv4() && configuredHost != null && !isIpAddress(configuredHost)) {
            connectHost = firstIpv4Address(List.of(InetAddress.getAllByName(configuredHost)));
            if (connectHost.isBlank()) {
                connectHost = configuredHost;
            }
        }
        return new SmtpEndpoint(connectHost, serverName);
    }

    private ChannelAccountEntity requireOwnedEmailAccount(UUID ownerId) {
        requireOwner(ownerId);
        List<ChannelAccountEntity> accounts = channelAccountMapper.findByOwnerAndChannelType(ownerId, "email");
        if (accounts.size() != 1) {
            throw new EmailException("CHANNEL_ACCOUNT_REQUIRED", "Exactly one active email channel account is required");
        }
        return accounts.get(0);
    }

    private static void requireOwner(UUID ownerId) {
        if (ownerId == null) throw new EmailException("AUTHENTICATION_REQUIRED", "Authentication is required");
    }

    private Map<String, String> decrypt(ChannelAccountEntity account) {
        if (credentialCipher == null || account.getEncryptedConfig() == null
                || account.getEncryptedConfig().isBlank() || "{}".equals(account.getEncryptedConfig().trim())) {
            return Map.of();
        }
        try {
            return credentialCipher.decrypt(account.getEncryptedConfig());
        } catch (CredentialCipher.CredentialDecryptionException e) {
            throw new EmailException("CHANNEL_ACCOUNT_CREDENTIALS_UNAVAILABLE", "Email account credentials unavailable", e);
        }
    }

    private record SmtpSettings(String host, int port, boolean ssl, boolean startTls,
                                String user, String password, String from, String fromName,
                                boolean resolveIpv4, String localhost) {
        static SmtpSettings from(AppConfig config, Map<String, String> saved) {
            String host = value(saved, "smtpHost", config.smtpHost());
            String user = value(saved, "smtpUser", config.smtpUser());
            String password = value(saved, "smtpPassword", config.smtpPassword());
            String from = value(saved, "mailFrom", config.mailFrom());
            if (from == null || from.isBlank()) from = user == null ? "" : user;
            int port = integer(saved, "smtpPort", config.smtpPort(), 465);
            boolean ssl = bool(saved, "smtpSsl", config.smtpSsl());
            boolean startTls = bool(saved, "smtpStartTls", config.smtpStartTls());
            return new SmtpSettings(host, port, ssl, startTls, user, password, from,
                    config.mailFromName(), config.smtpResolveIpv4(), config.smtpLocalhost());
        }
        private static String value(Map<String,String> saved, String key, String fallback) {
            String v = saved.get(key); return v == null || v.isBlank() ? fallback : v;
        }
        private static int integer(Map<String,String> saved, String key, String fallback, int defaultValue) {
            try { return Integer.parseInt(value(saved, key, fallback)); } catch (Exception e) { return defaultValue; }
        }
        private static boolean bool(Map<String,String> saved, String key, boolean fallback) {
            String v = saved.get(key); return v == null || v.isBlank() ? fallback : Boolean.parseBoolean(v);
        }
    }

    static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static long positiveOrDefault(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private static int positiveIntOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    static String messageIdHeader(String from, String smtpHost) {
        return "<" + UUID.randomUUID() + "." + Instant.now().toEpochMilli() + "@" + messageIdDomain(from, smtpHost) + ">";
    }

    static String messageIdDomain(String from, String smtpHost) {
        String email = ContactPointUtil.extractEmail(from);
        int at = email.lastIndexOf('@');
        if (at >= 0 && at + 1 < email.length()) {
            return email.substring(at + 1).toLowerCase();
        }
        String host = smtpHost == null ? "" : smtpHost.trim().toLowerCase();
        if (host.startsWith("smtp.") && host.length() > 5) {
            return host.substring(5);
        }
        return host.isBlank() ? "localhost.localdomain" : host;
    }

    static String smtpServername(String smtpHost, String smtpUsername) {
        String host = smtpHost == null ? "" : smtpHost.trim().toLowerCase();
        if (!isIpAddress(host)) {
            return host;
        }
        String email = ContactPointUtil.extractEmail(smtpUsername);
        int at = email.lastIndexOf('@');
        if (at >= 0 && at + 1 < email.length()) {
            return "smtp." + email.substring(at + 1).toLowerCase();
        }
        return host;
    }

    static String firstIpv4Address(List<InetAddress> addresses) {
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address) {
                return address.getHostAddress();
            }
        }
        return "";
    }

    static boolean isIpAddress(String value) {
        return value != null && value.trim().matches("(\\d{1,3}\\.){3}\\d{1,3}");
    }

    private record SmtpEndpoint(String connectHost, String serverName) {}

    static final class SniSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();
        private final String serverName;

        SniSocketFactory(String serverName) {
            this.serverName = serverName;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return applySni(delegate.createSocket(socket, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return applySni(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return applySni(delegate.createSocket(host, port, localHost, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return applySni(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return applySni(delegate.createSocket(address, port, localAddress, localPort));
        }

        private Socket applySni(Socket socket) {
            if (socket instanceof SSLSocket sslSocket && !isIpAddress(serverName)) {
                SSLParameters parameters = sslSocket.getSSLParameters();
                parameters.setServerNames(List.of(new SNIHostName(serverName)));
                sslSocket.setSSLParameters(parameters);
            }
            return socket;
        }
    }
}
