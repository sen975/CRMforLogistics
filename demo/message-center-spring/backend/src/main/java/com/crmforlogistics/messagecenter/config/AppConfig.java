package com.crmforlogistics.messagecenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app")
public record AppConfig(
        @DefaultValue("8099") int port,
        @DefaultValue("${user.dir}/data") String dataDir,
        String webBindAddress,
        String databaseUrl,
        String databaseUser,
        String databasePasswordFile,
        boolean localDevMode,
        String custSpaceId,
        String chatappFrom,
        String chatappTo,
        String chatappChannelType,
        @DefaultValue("true") boolean chatappSyncEnabled,
        String aliyunAccessKeyId,
        String aliyunAccessKeySecret,
        String camsRegion,
        String camsEndpoint,
        String smtpHost,
        String smtpPort,
        String smtpUser,
        String smtpPasswordFile,
        String smtpPassword,
        @DefaultValue("true") boolean smtpSsl,
        @DefaultValue("false") boolean smtpStartTls,
        @DefaultValue("true") boolean smtpResolveIpv4,
        String smtpLocalhost,
        String mailFrom,
        String mailFromName,
        String imapHost,
        String imapPort,
        String imapUser,
        String imapPasswordFile,
        String imapPassword,
        @DefaultValue("true") boolean imapSsl,
        @DefaultValue("10") int receiveLimit,
        @DefaultValue("auto") String mailProvider,
        @DefaultValue("true") boolean imap139UseOpenssl,
        @DefaultValue("/usr/bin/openssl") String opensslBin,
        @DefaultValue("false") boolean emailSyncEnabled,
        @DefaultValue("INBOX") String inboxFolder,
        @DefaultValue("Sent") String sentFolder
) {}
