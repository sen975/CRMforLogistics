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
        String imapHost,
        String imapPort,
        String imapUser,
        String imapPasswordFile
) {}
