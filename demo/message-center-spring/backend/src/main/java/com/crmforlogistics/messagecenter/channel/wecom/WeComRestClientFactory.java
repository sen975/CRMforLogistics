package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComRestClientFactory {
    private final String baseUrl;
    private final Duration configuredTimeout;

    public WeComRestClientFactory(AppConfig config) {
        this.baseUrl = config.wecomApiBaseUrl();
        this.configuredTimeout = validate(Duration.ofSeconds(config.wecomApiTimeoutSeconds()));
    }

    public RestClient create() {
        return create(configuredTimeout);
    }

    public RestClient create(Duration requestedTimeout) {
        Duration timeout = validate(requestedTimeout);
        if (timeout.compareTo(configuredTimeout) > 0) timeout = configuredTimeout;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    private static Duration validate(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("WeCom HTTP timeout must be between 1ns and 5 minutes");
        }
        return timeout;
    }
}
