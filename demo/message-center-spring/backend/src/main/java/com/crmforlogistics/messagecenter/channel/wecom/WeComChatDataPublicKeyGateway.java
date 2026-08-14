package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.service.wecom.WeComChatDataCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComChatDataPublicKeyGateway {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_ACCESS_TOKEN_CHARS = 4096;
    private static final int MAX_PUBLIC_KEY_BYTES = 16_384;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WeComChatDataPublicKeyGateway(AppConfig config, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(config.wecomApiBaseUrl())
                .build();
    }

    public void register(String accessToken, WeComChatDataCrypto.PublicKeyMaterial material) {
        try {
            require(accessToken != null && !accessToken.isBlank()
                    && accessToken.length() <= MAX_ACCESS_TOKEN_CHARS);
            require(material != null && material.version() > 0 && material.bitLength() == 2048);
            require(material.pem() != null
                    && material.pem().getBytes(StandardCharsets.US_ASCII).length <= MAX_PUBLIC_KEY_BYTES
                    && material.pem().startsWith("-----BEGIN PUBLIC KEY-----\n")
                    && material.pem().endsWith("-----END PUBLIC KEY-----\n"));

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("public_key", material.pem());
            requestBody.put("public_key_ver", material.version());
            String path = "/cgi-bin/chatdata/set_public_key?access_token="
                    + URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
            String response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(requestBody))
                    .retrieve()
                    .body(String.class);
            require(response != null && response.length() <= MAX_RESPONSE_BYTES);
            JsonNode body = objectMapper.readTree(response);
            require(body.has("errcode") && body.get("errcode").isNumber());
            int errcode = body.get("errcode").asInt();
            if (errcode != 0) throw failed(errcode, null);
        } catch (WeComChatDataException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failed(exception);
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw failed(null);
    }

    private static WeComChatDataException failed(Throwable cause) {
        return failed(null, cause);
    }

    private static WeComChatDataException failed(Integer upstreamErrcode, Throwable cause) {
        return new WeComChatDataException("WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FAILED", 503,
                "企业微信会话存档公钥注册失败", upstreamErrcode, cause);
    }
}
