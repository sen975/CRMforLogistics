package com.crmforlogistics.messagecenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "funasr")
public record FunAsrConfig(
        @DefaultValue("http://funasr:8000") String baseUrl,
        @DefaultValue("sensevoice") String model,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("1800s") Duration requestTimeout,
        @DefaultValue("10485760") long maxResponseBytes
) {
    public FunAsrConfig {
        if (maxResponseBytes < 1 || maxResponseBytes > 100_000_000) {
            throw new IllegalArgumentException("funasr.max-response-bytes is outside its bounds");
        }
    }
}
