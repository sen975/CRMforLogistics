package com.crmforlogistics.messagecenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "call-record")
public record CallRecordConfig(
        @DefaultValue("data/call-records") String dataDir,
        @DefaultValue("104857600") long maxAudioBytes,
        @DefaultValue("7200") int maxDurationSeconds,
        @DefaultValue("10737418240") long storageMaxBytes,
        @DefaultValue("10000") int maxRecords,
        @DefaultValue("64") int queueCapacity,
        @DefaultValue("1") int workerConcurrency,
        @DefaultValue("2100") int leaseSeconds,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("10485760") long maxResponseBytes,
        @DefaultValue("20000") int maxSegments,
        @DefaultValue("20") int maxRevisions,
        @DefaultValue("300") int audioSessionTtlSeconds,
        @DefaultValue("8") int audioSessionMaxPerActor,
        @DefaultValue("256") int audioSessionMaxActive
) {}
