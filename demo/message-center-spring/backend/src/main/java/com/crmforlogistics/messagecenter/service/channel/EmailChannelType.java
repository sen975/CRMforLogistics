package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.channel.email.EmailSyncService;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * 邮件渠道定义（{@code channel_accounts.channel_type = 'email'}）。
 *
 * <p>原先写在 {@code ChannelAccountService} 的凭证字段表与 {@code normalizeIdentifier}
 * 里的邮件专属知识，现在归到这里。
 */
@Service
public class EmailChannelType implements ChannelType {

    private static final Set<String> CREDENTIAL_FIELDS = Set.of(
            "smtpHost", "smtpPort", "smtpSsl", "smtpUser", "smtpPassword",
            "imapHost", "imapPort", "imapSsl", "imapUser", "imapPassword", "provider", "mailFrom");
    private static final Set<String> SECRET_FIELDS = Set.of("smtpPassword", "imapPassword");

    private final EmailSyncService emailSyncService;

    public EmailChannelType(EmailSyncService emailSyncService) {
        this.emailSyncService = emailSyncService;
    }

    @Override
    public String key() {
        return "email";
    }

    @Override
    public Set<String> credentialFields() {
        return CREDENTIAL_FIELDS;
    }

    @Override
    public Set<String> secretFields() {
        return SECRET_FIELDS;
    }

    /** 邮箱地址大小写不敏感，统一小写落库以便唯一性判断。 */
    @Override
    public String normalizeIdentifier(String identifier) {
        return trimmed(identifier).toLowerCase();
    }

    @Override
    public Object syncAccount(UUID ownerId, UUID accountId) throws Exception {
        return emailSyncService.receiveLatest(accountId, ownerId);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
