package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComAuthorizationGateway {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern UPSTREAM_HINT = Pattern.compile(
            "(?i)(?:^|\\s)hint\\s*:\\s*\\[([A-Za-z0-9_-]{1,128})]");
    private final AppConfig config;
    private final WeComRestClientFactory restClients;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, SuiteTicket> tickets = new ConcurrentHashMap<>();
    private final Map<String, SuiteToken> suiteTokens = new ConcurrentHashMap<>();

    @Autowired
    public WeComAuthorizationGateway(AppConfig config, ObjectMapper objectMapper,
                                     WeComRestClientFactory restClients) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.clock = Clock.systemUTC();
        this.restClients = restClients;
    }

    public WeComAuthorizationGateway(AppConfig config, ObjectMapper objectMapper) {
        this(config, objectMapper, new WeComRestClientFactory(config));
    }

    public void acceptSuiteTicket(String suiteId, String suiteTicket, Instant receivedAt)
            throws WeComException {
        require(suiteId, "suiteId", 128);
        require(suiteTicket, "suiteTicket", 512);
        if (receivedAt == null) throw new IllegalArgumentException("receivedAt is required");
        if (!isKnownSuite(suiteId)) {
            throw new WeComException("WECOM_CALLBACK_SUITE_MISMATCH", 403, "企业微信 SuiteId 不匹配");
        }
        tickets.put(suiteId, new SuiteTicket(suiteTicket, receivedAt.plusSeconds(30 * 60)));
        suiteTokens.remove(suiteId);
    }

    public String suiteAccessToken(String suiteId) throws WeComException {
        require(suiteId, "suiteId", 128);
        long now = clock.instant().getEpochSecond();
        SuiteToken cached = suiteTokens.get(suiteId);
        if (cached != null && now < cached.expiresAtEpochSecond - 300) return cached.token;
        SuiteTicket ticket = tickets.get(suiteId);
        if (ticket == null || !clock.instant().isBefore(ticket.expiresAt)) {
            throw new WeComException("WECOM_SUITE_TICKET_NOT_READY", 503, "企业微信 suite_ticket 尚未就绪");
        }
        JsonNode body = postJson("/cgi-bin/service/get_suite_token", Map.of(
                "suite_id", suiteId,
                "suite_secret", suiteSecret(suiteId),
                "suite_ticket", ticket.value), TIMEOUT);
        String token = successString(body, "suite_access_token", "/cgi-bin/service/get_suite_token");
        int expiresIn = positiveInt(body, "expires_in", 7200);
        suiteTokens.put(suiteId, new SuiteToken(token, now + expiresIn));
        return token;
    }

    public PermanentCodeResponse getPermanentCode(String authCode) throws WeComException {
        require(authCode, "authCode", 512);
        String path = "/cgi-bin/service/v2/get_permanent_code";
        JsonNode body = postJson(path + "?suite_access_token="
                + encode(suiteAccessToken(config.wecomSuiteId())), path, Map.of("auth_code", authCode));
        String permanentCode = successString(body, "permanent_code", path);
        JsonNode authInfo = body.has("auth_corp_info") && body.get("auth_corp_info").isObject()
                ? body.get("auth_corp_info") : null;
        String corpId = authInfo != null ? string(authInfo, "corpid") : "";
        if (corpId.isBlank()) corpId = string(body, "auth_corpid");
        require(corpId, "authCorpId", 128);
        return new PermanentCodeResponse(corpId, permanentCode);
    }

    public AuthorizationInfo getAuthInfo(String authCorpId, String permanentCode) throws WeComException {
        require(authCorpId, "authCorpId", 128);
        require(permanentCode, "permanentCode", 512);
        String path = "/cgi-bin/service/v2/get_auth_info";
        JsonNode body = postJson(path + "?suite_access_token="
                + encode(suiteAccessToken(config.wecomSuiteId())), path, Map.of("auth_corpid", authCorpId,
                "permanent_code", permanentCode));
        JsonNode authCorpInfo = body.has("auth_corp_info") && body.get("auth_corp_info").isObject()
                ? body.get("auth_corp_info") : null;
        String corpId = authCorpInfo != null ? string(authCorpInfo, "corpid") : "";
        String corpName = authCorpInfo != null ? string(authCorpInfo, "corp_name") : "";
        JsonNode authInfoNode = body.has("auth_info") && body.get("auth_info").isObject()
                ? body.get("auth_info") : null;
        require(corpId, "authCorpId", 128);
        java.util.ArrayList<AuthorizedAgent> agents = new java.util.ArrayList<>();
        if (authInfoNode != null && authInfoNode.has("agent") && authInfoNode.get("agent").isArray()) {
            for (JsonNode element : authInfoNode.get("agent")) {
                if (element.isObject()) {
                    String agentId = string(element, "agentid");
                    if (!agentId.isBlank()) agents.add(new AuthorizedAgent(agentId));
                }
            }
        }
        if (agents.isEmpty()) throw new WeComException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 500,
                "企业微信授权信息缺少 AgentID");
        return new AuthorizationInfo(corpId, corpName, List.copyOf(agents));
    }

    public CorpTokenResponse getDevelopedAppToken(String authCorpId, String developedAppSecret,
                                                  Duration timeout) throws WeComException {
        require(authCorpId, "authCorpId", 128);
        require(developedAppSecret, "developedAppSecret", 512);
        String path = "/cgi-bin/gettoken";
        JsonNode body = getJson(path + "?corpid=" + encode(authCorpId)
                + "&corpsecret=" + encode(developedAppSecret), path, timeout);
        return new CorpTokenResponse(successString(body, "access_token", path),
                positiveInt(body, "expires_in", 7200));
    }

    public LoginIdentity getLoginIdentity(String authCorpId, String accessToken,
                                          String code, Duration timeout) throws WeComException {
        require(authCorpId, "authCorpId", 128);
        require(accessToken, "accessToken", 4096);
        require(code, "code", 512);
        String diagnosticPath = "/cgi-bin/auth/getuserinfo";
        String path = diagnosticPath + "?access_token=" + encode(accessToken)
                + "&code=" + encode(code);
        JsonNode body = getJson(path, diagnosticPath, timeout);
        String userId = string(body, "userid");
        if (userId.isBlank()) {
            throw new WeComException("WECOM_LOGIN_IDENTITY_UNAVAILABLE", 403,
                    "企业微信登录未返回企业成员身份", null, diagnosticPath, 200, null);
        }
        require(authCorpId, "authCorpId", 128);
        require(userId, "userId", 128);
        return new LoginIdentity(authCorpId, userId);
    }

    private JsonNode getJson(String requestPath, String diagnosticPath, Duration timeout)
            throws WeComException {
        requireTimeout(timeout);
        try {
            String response = restClients.create(timeout).get()
                    .uri(requestPath)
                    .retrieve()
                    .body(String.class);
            if (response == null || response.length() > MAX_RESPONSE_BYTES) {
                throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                        "企业微信上游响应异常", null, diagnosticPath, 200, null);
            }
            JsonNode body = objectMapper.readTree(response);
            if (!body.isObject()) {
                throw new WeComException("WECOM_UPSTREAM_INVALID_RESPONSE", 503,
                        "企业微信上游响应格式无效: " + diagnosticPath,
                        null, diagnosticPath, 200, null);
            }
            JsonNode errcodeNode = body.get("errcode");
            if (errcodeNode != null && !errcodeNode.canConvertToInt()) {
                throw new WeComException("WECOM_UPSTREAM_INVALID_RESPONSE", 503,
                        "企业微信上游 errcode 格式无效: " + diagnosticPath,
                        null, diagnosticPath, 200, null);
            }
            if (errcodeNode != null && errcodeNode.asInt() != 0) {
                int errcode = errcodeNode.asInt();
                String errmsg = string(body, "errmsg");
                String diagnosticMessage = errmsg.isBlank()
                        ? "errcode=" + errcode
                        : "errcode=" + errcode + ": " + errmsg;
                throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                        "企业微信上游返回错误: " + diagnosticMessage,
                        errcode, diagnosticPath, 200, extractHint(errmsg));
            }
            return body;
        } catch (WeComException e) {
            throw e;
        } catch (Exception e) {
            throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游服务暂时不可用", null, diagnosticPath, null, null);
        }
    }

    private boolean isKnownSuite(String suiteId) {
        return suiteId.equals(config.wecomSuiteId());
    }

    private String suiteSecret(String suiteId) throws WeComException {
        if (suiteId.equals(config.wecomSuiteId())) return config.wecomSuiteSecret();
        throw new WeComException("WECOM_CALLBACK_SUITE_MISMATCH", 403, "企业微信 SuiteId 不匹配");
    }

    private JsonNode postJson(String path, Map<String, String> values) throws WeComException {
        return postJson(path, path, values, TIMEOUT);
    }

    private JsonNode postJson(String path, Map<String, String> values, Duration timeout)
            throws WeComException {
        return postJson(path, path, values, timeout);
    }

    private JsonNode postJson(String requestPath, String diagnosticPath,
                              Map<String, String> values) throws WeComException {
        return postJson(requestPath, diagnosticPath, values, TIMEOUT);
    }

    private JsonNode postJson(String requestPath, String diagnosticPath,
                              Map<String, String> values, Duration timeout)
            throws WeComException {
        requireTimeout(timeout);
        try {
            String json = objectMapper.writeValueAsString(values);
            String response = restClients.create(timeout).post()
                    .uri(requestPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .body(String.class);
            if (response == null || response.length() > MAX_RESPONSE_BYTES) {
                throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                        "企业微信上游响应异常", null, diagnosticPath, 200, null);
            }
            JsonNode body = objectMapper.readTree(response);
            if (!body.isObject()) {
                throw new WeComException("WECOM_UPSTREAM_INVALID_RESPONSE", 503,
                        "企业微信上游响应格式无效: " + diagnosticPath,
                        null, diagnosticPath, 200, null);
            }
            JsonNode errcodeNode = body.get("errcode");
            if (errcodeNode != null && !errcodeNode.canConvertToInt()) {
                throw new WeComException("WECOM_UPSTREAM_INVALID_RESPONSE", 503,
                        "企业微信上游 errcode 格式无效: " + diagnosticPath,
                        null, diagnosticPath, 200, null);
            }
            if (errcodeNode != null && errcodeNode.asInt() != 0) {
                int errcode = errcodeNode.asInt();
                String errmsg = string(body, "errmsg");
                String diagnosticMessage = errmsg.isBlank()
                        ? "errcode=" + errcode
                        : "errcode=" + errcode + ": " + errmsg;
                throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                        "企业微信上游返回错误: " + diagnosticMessage,
                        errcode, diagnosticPath, 200, extractHint(errmsg));
            }
            return body;
        } catch (WeComException e) {
            throw e;
        } catch (Exception e) {
            throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游服务暂时不可用", null, diagnosticPath, null, null);
        }
    }

    private static String extractHint(String errmsg) {
        if (errmsg == null || errmsg.isBlank() || errmsg.length() > 4096) return null;
        Matcher matcher = UPSTREAM_HINT.matcher(errmsg);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String successString(JsonNode body, String field, String upstreamPath)
            throws WeComException {
        String value = string(body, field);
        if (value.isBlank() || value.length() > 4096) {
            throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游响应缺少凭证", null, upstreamPath, 200, null);
        }
        return value;
    }

    private static int positiveInt(JsonNode body, String field, int fallback) {
        return body.has(field) && body.get(field).isNumber() ? Math.max(1, body.get(field).asInt()) : fallback;
    }

    private static String string(JsonNode object, String field) {
        JsonNode node = object.get(field);
        return node != null && !node.isNull() ? node.asText("") : "";
    }

    private static void require(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " is required and must not exceed " + max + " characters");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void requireTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("WeCom request timeout must be positive");
        }
    }

    public record PermanentCodeResponse(String authCorpId, String permanentCode) {}
    public record AuthorizationInfo(String authCorpId, String corpName, List<AuthorizedAgent> agents) {
        public AuthorizationInfo(String authCorpId, List<AuthorizedAgent> agents) {
            this(authCorpId, "", agents);
        }
    }
    public record AuthorizedAgent(String agentId) {}
    public record CorpTokenResponse(String accessToken, int expiresIn) {}
    public record LoginIdentity(String corpId, String userId) {}
    private record SuiteTicket(String value, Instant expiresAt) {}
    private record SuiteToken(String token, long expiresAtEpochSecond) {}
}
