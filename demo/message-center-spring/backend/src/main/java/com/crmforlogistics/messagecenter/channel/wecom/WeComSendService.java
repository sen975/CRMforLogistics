package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComSendService {
    private final AppConfig config;
    private final WeComInstallationService installationService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public WeComSendService(AppConfig config,
                             WeComInstallationService installationService,
                             ObjectMapper objectMapper,
                             WeComRestClientFactory restClients) {
        this(config, installationService, objectMapper, restClients.create());
    }

    public WeComSendService(AppConfig config,
                            WeComInstallationService installationService,
                            ObjectMapper objectMapper) {
        this(config, installationService, objectMapper, RestClient.create());
    }

    private WeComSendService(AppConfig config,
                             WeComInstallationService installationService,
                             ObjectMapper objectMapper,
                             RestClient restClient) {
        this.config = config;
        this.installationService = installationService;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    public SendResult send(String corpId, String agentId, String toUser,
                            String text) throws WeComException {
        String token = installationService.accessToken(
                config.wecomSuiteId(), corpId);
        try {
            Map<String, Object> body = Map.of(
                    "touser", toUser,
                    "msgtype", "text",
                    "agentid", Integer.parseInt(agentId),
                    "text", Map.of("content", text));
            String json = objectMapper.writeValueAsString(body);
            String response = restClient.post()
                    .uri("/cgi-bin/message/send?access_token={token}", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(response);
            int errcode = node.path("errcode").asInt(-1);
            if (errcode != 0) {
                throw new WeComException("WECOM_SEND_FAILED", 502,
                        "企业微信消息发送失败: " + node.path("errmsg").asText(),
                        errcode, "/cgi-bin/message/send", 200, null);
            }
            String msgId = node.path("msgid").asText("");
            return new SendResult(msgId, agentId, toUser, text, "sent");
        } catch (WeComException e) {
            throw e;
        } catch (Exception e) {
            throw new WeComException("WECOM_SEND_FAILED", 502,
                    "企业微信消息发送失败");
        }
    }

    public record SendResult(String messageId, String from, String to,
                              String text, String status) {}
}
