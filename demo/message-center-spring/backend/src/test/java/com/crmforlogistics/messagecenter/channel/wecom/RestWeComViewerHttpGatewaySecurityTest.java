package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.service.wecom.WeComAccessTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestWeComViewerHttpGatewaySecurityTest {

    @Test
    void transportFailureDoesNotRetainAccessTokenInExceptionChain() {
        String token = "viewer-access-token-must-not-leak";
        AppConfig config = mock(AppConfig.class);
        WeComAccessTokenService accessTokens = mock(WeComAccessTokenService.class);
        when(accessTokens.accessToken(installation())).thenReturn(token);
        WeComRestClientFactory restClients = mock(WeComRestClientFactory.class);
        when(restClients.create()).thenReturn(
                RestClient.builder()
                        .baseUrl("http://127.0.0.1:1")
                        .requestInterceptor((request, body, execution) -> {
                            throw new IOException("transport failed for " + request.getURI());
                        })
                        .build());
        RestWeComViewerHttpGateway gateway = new RestWeComViewerHttpGateway(
                config, new ObjectMapper(), accessTokens,
                mock(WeComAuthorizationGateway.class), restClients);

        Throwable thrown = catchThrowable(() -> gateway.fetchCorpJsapiTicket(installation()));

        assertThat(thrown).isInstanceOf(WeComException.class);
        StringWriter stack = new StringWriter();
        thrown.printStackTrace(new PrintWriter(stack));
        assertThat(stack.toString()).doesNotContain(token).doesNotContain("access_token=");
    }

    private static ResolvedInstallation installation() {
        return new ResolvedInstallation("installation", "suite", "corp", "agent", "permanent", 1L);
    }
}
