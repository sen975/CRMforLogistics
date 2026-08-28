package com.crmforlogistics.messagecenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ai-topic")
public record AiTopicConfig(
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("gpt-4o-mini") String model,
        @DefaultValue("30") int timeoutSeconds,
        @DefaultValue("200") int maxInputRecords,
        @DefaultValue("262144") long maxInputBytes,
        @DefaultValue("0.65") double matchThreshold,
        @DefaultValue("1") int workerConcurrency,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("120") int leaseSeconds,
        @DefaultValue("30") int pollIntervalSeconds
) {
    public AiTopicConfig {
        if (timeoutSeconds <= 0 || timeoutSeconds > 300) throw new IllegalArgumentException("ai-topic timeoutSeconds must be 1..300");
        if (maxInputRecords <= 0 || maxInputRecords > 10_000) throw new IllegalArgumentException("ai-topic maxInputRecords must be 1..10000");
        if (maxInputBytes <= 0 || maxInputBytes > 8_388_608) throw new IllegalArgumentException("ai-topic maxInputBytes must be 1..8388608");
        if (matchThreshold < 0 || matchThreshold > 1) throw new IllegalArgumentException("ai-topic matchThreshold must be 0..1");
        if (workerConcurrency <= 0 || workerConcurrency > 16) throw new IllegalArgumentException("ai-topic workerConcurrency must be 1..16");
        if (maxAttempts <= 0 || maxAttempts > 20) throw new IllegalArgumentException("ai-topic maxAttempts must be 1..20");
        if (leaseSeconds <= 0 || leaseSeconds > 3600) throw new IllegalArgumentException("ai-topic leaseSeconds must be 1..3600");
        if (pollIntervalSeconds <= 0 || pollIntervalSeconds > 3600) throw new IllegalArgumentException("ai-topic pollIntervalSeconds must be 1..3600");
    }
}
