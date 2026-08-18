package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.service.wecom.WeComAccessTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class RestWeComViewerHttpGateway implements WeComViewerHttpGateway {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final AppConfig config;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final WeComAccessTokenService accessTokens;
    private final WeComAuthorizationGateway authorizationGateway;

    public RestWeComViewerHttpGateway(AppConfig config, ObjectMapper objectMapper,
                                      WeComAccessTokenService accessTokens,
                                      WeComAuthorizationGateway authorizationGateway,
                                      WeComRestClientFactory restClients) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.accessTokens = accessTokens;
        this.authorizationGateway = authorizationGateway;
        this.restClient = restClients.create();
    }

    @Override
    public TicketResponse fetchCorpJsapiTicket() {
        return fetchTicket("/cgi-bin/get_jsapi_ticket?access_token=" + encode(corpAccessToken()));
    }

    @Override
    public TicketResponse fetchAgentJsapiTicket() {
        return fetchTicket("/cgi-bin/ticket/get?access_token=" + encode(corpAccessToken())
                + "&type=agent_config");
    }

    @Override
    public TicketResponse fetchCorpJsapiTicket(ResolvedInstallation installation) {
        return fetchTicket("/cgi-bin/get_jsapi_ticket?access_token="
                + encode(accessTokens.accessToken(installation)));
    }

    @Override
    public TicketResponse fetchAgentJsapiTicket(ResolvedInstallation installation) {
        return fetchTicket("/cgi-bin/ticket/get?access_token="
                + encode(accessTokens.accessToken(installation)) + "&type=agent_config");
    }

    @Override
    public String exchangeLoginCode(String code) {
        throw new WeComException("WECOM_LOGIN_SUITE_NOT_CONFIGURED", 503,
                "企业微信登录授权 Suite 网关不可用");
    }

    @Override
    public WeComAuthorizationGateway.LoginIdentity exchangeLoginIdentity(String code) {
        return authorizationGateway.getLoginIdentity(code, TIMEOUT);
    }

    private String corpAccessToken() {
        if (config.wecomCorpId().isBlank() || config.wecomSecret().isBlank()) {
            throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信 corp id 或 secret 未配置");
        }
        return authorizationGateway.getDevelopedAppToken(
                config.wecomCorpId(), config.wecomSecret(), TIMEOUT).accessToken();
    }

    private TicketResponse fetchTicket(String path) {
        JsonNode body = getJson(path);
        int errcode = body.has("errcode") && body.get("errcode").isNumber()
                ? body.get("errcode").asInt() : -1;
        return new TicketResponse(errcode, string(body, "errmsg"), string(body, "ticket"),
                body.has("expires_in") && body.get("expires_in").isNumber()
                        ? body.get("expires_in").asInt() : 7200);
    }

    private JsonNode getJson(String path) {
        try {
            String response = restClient.get().uri(path).retrieve().body(String.class);
            if (response == null || response.length() > MAX_RESPONSE_BYTES) {
                throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                        "企业微信上游响应异常");
            }
            return objectMapper.readTree(response);
        } catch (WeComException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游服务暂时不可用");
        }
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText("") : "";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
