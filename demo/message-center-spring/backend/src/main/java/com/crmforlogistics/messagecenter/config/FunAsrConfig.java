package com.crmforlogistics.messagecenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "funasr")
public record FunAsrConfig(
        @DefaultValue("http://funasr:8000") String baseUrl,
        @DefaultValue("sensevoice") String model,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("1800s") Duration requestTimeout
) {}
