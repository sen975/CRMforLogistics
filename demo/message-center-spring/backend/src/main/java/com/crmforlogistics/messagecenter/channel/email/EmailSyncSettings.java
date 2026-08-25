package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import org.slf4j.Logger;

import java.util.Map;

/** Effective email settings: channel-account values override process defaults. */
record EmailSyncSettings(
        String imapHost,
        String imapPort,
        String imapUser,
        String imapPassword,
        boolean imapSsl,
        int receiveLimit,
        String mailProvider,
        boolean imap139UseOpenssl,
        String opensslBin,
        String inboxFolder,
        String sentFolder) {

    static EmailSyncSettings from(AppConfig config, ChannelAccountEntity account,
                                  CredentialCipher cipher, Logger log) {
        Map<String, String> saved = Map.of();
        if (account != null && account.getEncryptedConfig() != null
                && !account.getEncryptedConfig().isBlank()
                && !"{}".equals(account.getEncryptedConfig().trim()) && cipher != null) {
            try {
                saved = cipher.decrypt(account.getEncryptedConfig());
            } catch (CredentialCipher.CredentialDecryptionException exception) {
                log.warn("event=email.channel_credentials_unavailable code={} accountId={}",
                        exception.code(), account.getId());
            }
        }
        return new EmailSyncSettings(
                override(saved, "imapHost", config.imapHost()),
                override(saved, "imapPort", config.imapPort()),
                override(saved, "imapUser", firstNonBlank(
                        config.imapUser(), account == null ? null : account.getAccountIdentifier())),
                override(saved, "imapPassword", config.imapPassword()),
                bool(saved, "imapSsl", config.imapSsl()),
                positive(saved, "receiveLimit", config.receiveLimit()),
                override(saved, "mailProvider", config.mailProvider()),
                bool(saved, "imap139UseOpenssl", config.imap139UseOpenssl()),
                override(saved, "opensslBin", config.opensslBin()),
                override(saved, "inboxFolder", config.inboxFolder()),
                override(saved, "sentFolder", config.sentFolder()));
    }

    private static String override(Map<String, String> saved, String key, String fallback) {
        String value = saved.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static boolean bool(Map<String, String> saved, String key, boolean fallback) {
        String value = saved.get(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int positive(Map<String, String> saved, String key, int fallback) {
        String value = saved.get(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
