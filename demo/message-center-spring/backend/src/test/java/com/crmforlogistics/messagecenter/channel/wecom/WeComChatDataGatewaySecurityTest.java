package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeComChatDataGatewaySecurityTest {

    @Test
    void transportFailureDoesNotRetainAccessTokenInExceptionChain() {
        String token = "chatdata-access-token-must-not-leak";
        AppConfig config = mock(AppConfig.class);
        when(config.wecomChatDataProgramId()).thenReturn("program");
        when(config.wecomChatDataAbilityId()).thenReturn("ability");
        RestClient client = RestClient.builder()
                .baseUrl("http://127.0.0.1:1")
                .requestInterceptor((request, body, execution) -> {
                    throw new IOException("transport failed for " + request.getURI());
                })
                .build();
        WeComChatDataGateway gateway = new WeComChatDataGateway(
                config, new ObjectMapper(), client, (installation, timeout) -> token);

        Throwable thrown = catchThrowable(() -> gateway.sync(installation(), "", 1,
                Duration.ofSeconds(1)));

        assertThat(thrown).isInstanceOf(WeComChatDataException.class);
        StringWriter stack = new StringWriter();
        thrown.printStackTrace(new PrintWriter(stack));
        assertThat(stack.toString()).doesNotContain(token).doesNotContain("access_token=");
    }

    private static ResolvedInstallation installation() {
        return new ResolvedInstallation("installation", "suite", "corp", "agent", "permanent", 1L);
    }
}
