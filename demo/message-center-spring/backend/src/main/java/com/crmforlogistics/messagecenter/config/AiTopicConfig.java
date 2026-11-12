package com.crmforlogistics.messagecenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
        @DefaultValue("30") int pollIntervalSeconds,
        @DefaultValue("262144") long auditMaxRequestBytes,
        @DefaultValue("524288") long auditMaxResponseBytes,
        @DefaultValue("360") int quietWindowSeconds
) {
    public AiTopicConfig(String baseUrl, String apiKey, String model, int timeoutSeconds, int maxInputRecords,
                         long maxInputBytes, double matchThreshold, int workerConcurrency, int maxAttempts,
                         int leaseSeconds, int pollIntervalSeconds) {
        this(baseUrl, apiKey, model, timeoutSeconds, maxInputRecords, maxInputBytes, matchThreshold,
                workerConcurrency, maxAttempts, leaseSeconds, pollIntervalSeconds, 262144, 524288, 360);
    }

    public AiTopicConfig(String baseUrl, String apiKey, String model, int timeoutSeconds, int maxInputRecords,
                         long maxInputBytes, double matchThreshold, int workerConcurrency, int maxAttempts,
                         int leaseSeconds, int pollIntervalSeconds, long auditMaxRequestBytes,
                         long auditMaxResponseBytes) {
        this(baseUrl, apiKey, model, timeoutSeconds, maxInputRecords, maxInputBytes, matchThreshold,
                workerConcurrency, maxAttempts, leaseSeconds, pollIntervalSeconds,
                auditMaxRequestBytes, auditMaxResponseBytes, 360);
    }

    @ConstructorBinding
    public AiTopicConfig {
        if (timeoutSeconds <= 0 || timeoutSeconds > 300) throw new IllegalArgumentException("ai-topic timeoutSeconds must be 1..300");
        if (maxInputRecords <= 0 || maxInputRecords > 10_000) throw new IllegalArgumentException("ai-topic maxInputRecords must be 1..10000");
        if (maxInputBytes <= 0 || maxInputBytes > 8_388_608) throw new IllegalArgumentException("ai-topic maxInputBytes must be 1..8388608");
        if (matchThreshold < 0 || matchThreshold > 1) throw new IllegalArgumentException("ai-topic matchThreshold must be 0..1");
        if (workerConcurrency <= 0 || workerConcurrency > 16) throw new IllegalArgumentException("ai-topic workerConcurrency must be 1..16");
        if (maxAttempts <= 0 || maxAttempts > 20) throw new IllegalArgumentException("ai-topic maxAttempts must be 1..20");
        if (leaseSeconds <= 0 || leaseSeconds > 3600) throw new IllegalArgumentException("ai-topic leaseSeconds must be 1..3600");
        if (pollIntervalSeconds <= 0 || pollIntervalSeconds > 3600) throw new IllegalArgumentException("ai-topic pollIntervalSeconds must be 1..3600");
        if (auditMaxRequestBytes < 256 || auditMaxRequestBytes > 8_388_608) throw new IllegalArgumentException("ai-topic auditMaxRequestBytes must be 256..8388608");
        if (auditMaxResponseBytes < 256 || auditMaxResponseBytes > 16_777_216) throw new IllegalArgumentException("ai-topic auditMaxResponseBytes must be 256..16777216");
        if (quietWindowSeconds < 60 || quietWindowSeconds > 86_400) throw new IllegalArgumentException("ai-topic quietWindowSeconds must be 60..86400");
    }
}
