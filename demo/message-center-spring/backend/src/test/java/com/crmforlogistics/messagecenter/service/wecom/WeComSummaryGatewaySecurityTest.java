package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComSummaryException;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeComSummaryGatewaySecurityTest {

    @Test
    void transportFailureDoesNotRetainAccessTokenOrSecretKeyInExceptionChain() {
        String token = "summary-access-token-must-not-leak";
        String secretKey = "summary-secret-key-must-not-leak";
        AppConfig config = mock(AppConfig.class);
        when(config.wecomChatDataProgramId()).thenReturn("program");
        when(config.wecomDailySummaryAbilityId()).thenReturn("summary");
        RestClient client = RestClient.builder()
                .baseUrl("http://127.0.0.1:1")
                .requestInterceptor((request, body, execution) -> {
                    throw new IOException("transport failed for " + request.getURI()
                            + " with body " + new String(body));
                })
                .build();
        WeComSummaryGateway gateway = new WeComSummaryGateway(
                config, new ObjectMapper(), client, (installation, timeout) -> token);

        Throwable thrown = catchThrowable(() -> gateway.submit(installation(),
                List.of(new WeComSummaryGateway.MessageReference("message", secretKey)),
                Duration.ofSeconds(1)));

        assertThat(thrown).isInstanceOf(WeComSummaryException.class);
        StringWriter stack = new StringWriter();
        thrown.printStackTrace(new PrintWriter(stack));
        assertThat(stack.toString())
                .doesNotContain(token)
                .doesNotContain(secretKey)
                .doesNotContain("access_token=");
    }

    private static ResolvedInstallation installation() {
        return new ResolvedInstallation("installation", "suite", "corp", "agent", "permanent", 1L);
    }
}
