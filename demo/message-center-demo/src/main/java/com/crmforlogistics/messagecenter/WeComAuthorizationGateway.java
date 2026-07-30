package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded HTTP gateway for service-provider WeCom credentials. */
public final class WeComAuthorizationGateway implements WeComAuthorizationClient {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final Config config;
    private final HttpClient client;
    private final URI apiBase;
    private final Clock clock;
    private final Map<String, SuiteTicket> tickets = new ConcurrentHashMap<>();
    private final Map<String, SuiteToken> suiteTokens = new ConcurrentHashMap<>();

    public WeComAuthorizationGateway(Config config) {
        this(config, HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
                URI.create(config.value("WECOM_API_BASE_URL", "https://qyapi.weixin.qq.com")), Clock.systemUTC());
    }

    WeComAuthorizationGateway(Config config, HttpClient client, URI apiBase, Clock clock) {
        this.config = config;
        this.client = client;
        this.apiBase = apiBase;
        this.clock = clock;
    }

    public void acceptSuiteTicket(String suiteId, String suiteTicket, Instant receivedAt)
            throws WeComAuthorizationException {
        require(suiteId, "suiteId", 128);
        require(suiteTicket, "suiteTicket", 512);
        if (receivedAt == null) throw new IllegalArgumentException("receivedAt is required");
        if (!suiteId.equals(config.wecomSuiteId())) {
            throw new WeComAuthorizationException("WECOM_CALLBACK_SUITE_MISMATCH", 403, "企业微信 SuiteId 不匹配");
        }
        tickets.put(suiteId, new SuiteTicket(suiteTicket, receivedAt.plusSeconds(30 * 60)));
        suiteTokens.remove(suiteId);
    }

    public String suiteAccessToken(String suiteId) throws WeComAuthorizationException {
        return suiteAccessToken(suiteId, TIMEOUT);
    }

    private String suiteAccessToken(String suiteId, Duration timeout) throws WeComAuthorizationException {
        require(suiteId, "suiteId", 128);
        long deadline = deadline(timeout);
        long now = clock.instant().getEpochSecond();
        SuiteToken cached = suiteTokens.get(suiteId);
        if (cached != null && now < cached.expiresAtEpochSecond() - 300) return cached.token();
        SuiteTicket ticket = tickets.get(suiteId);
        if (ticket == null || !clock.instant().isBefore(ticket.expiresAt())) {
            throw new WeComAuthorizationException("WECOM_SUITE_TICKET_NOT_READY", 503, "企业微信 suite_ticket 尚未就绪");
        }
        JsonObject body = postJson("/cgi-bin/service/get_suite_token", Map.of(
                "suite_id", config.wecomSuiteId(),
                "suite_secret", config.wecomSuiteSecret(),
                "suite_ticket", ticket.value()), remaining(deadline));
        String token = successString(body, "suite_access_token");
        int expiresIn = positiveInt(body, "expires_in", 7200);
        suiteTokens.put(suiteId, new SuiteToken(token, now + expiresIn));
        return token;
    }

    public PermanentCodeResponse getPermanentCode(String authCode) throws WeComAuthorizationException {
        require(authCode, "authCode", 512);
        JsonObject body = postJson("/cgi-bin/service/v2/get_permanent_code?suite_access_token="
                + encode(suiteAccessToken(config.wecomSuiteId())), Map.of("auth_code", authCode));
        String permanentCode = successString(body, "permanent_code");
        JsonObject authInfo = body.has("auth_corp_info") && body.get("auth_corp_info").isJsonObject()
                ? body.getAsJsonObject("auth_corp_info") : new JsonObject();
        String corpId = string(authInfo, "corpid");
        if (corpId.isBlank()) corpId = string(body, "auth_corpid");
        require(corpId, "authCorpId", 128);
        return new PermanentCodeResponse(corpId, permanentCode);
    }

    public AuthorizationInfo getAuthInfo(String authCorpId, String permanentCode) throws WeComAuthorizationException {
        require(authCorpId, "authCorpId", 128);
        require(permanentCode, "permanentCode", 512);
        JsonObject body = postJson("/cgi-bin/service/v2/get_auth_info?suite_access_token="
                + encode(suiteAccessToken(config.wecomSuiteId())), Map.of("auth_corpid", authCorpId,
                "permanent_code", permanentCode));
        String corpId = string(body, "auth_corpid");
        JsonObject authInfo = body.has("auth_info") && body.get("auth_info").isJsonObject()
                ? body.getAsJsonObject("auth_info") : new JsonObject();
        if (corpId.isBlank()) corpId = string(authInfo, "corpid");
        require(corpId, "authCorpId", 128);
        java.util.ArrayList<AuthorizedAgent> agents = new java.util.ArrayList<>();
        if (authInfo.has("agent") && authInfo.get("agent").isJsonArray()) {
            authInfo.getAsJsonArray("agent").forEach(element -> {
                if (element.isJsonObject()) {
                    String agentId = string(element.getAsJsonObject(), "agentid");
                    if (!agentId.isBlank()) agents.add(new AuthorizedAgent(agentId));
                }
            });
        }
        if (agents.isEmpty()) throw new WeComAuthorizationException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 500,
                "企业微信授权信息缺少 AgentID");
        return new AuthorizationInfo(corpId, List.copyOf(agents));
    }

    public CorpTokenResponse getCorpToken(String authCorpId, String permanentCode)
            throws WeComAuthorizationException {
        return getCorpToken(authCorpId, permanentCode, TIMEOUT);
    }

    public CorpTokenResponse getCorpToken(String authCorpId, String permanentCode, Duration timeout)
            throws WeComAuthorizationException {
        require(authCorpId, "authCorpId", 128);
        require(permanentCode, "permanentCode", 512);
        long deadline = deadline(timeout);
        JsonObject body = postJson("/cgi-bin/service/get_corp_token?suite_access_token="
                + encode(suiteAccessToken(config.wecomSuiteId(), remaining(deadline))), Map.of(
                "auth_corpid", authCorpId,
                "permanent_code", permanentCode), remaining(deadline));
        return new CorpTokenResponse(successString(body, "access_token"),
                positiveInt(body, "expires_in", 7200));
    }

    private JsonObject postJson(String path, Map<String, String> values) throws WeComAuthorizationException {
        return postJson(path, values, TIMEOUT);
    }

    private JsonObject postJson(String path, Map<String, String> values, Duration timeout)
            throws WeComAuthorizationException {
        try {
            JsonObject json = new JsonObject();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                json.addProperty(entry.getKey(), entry.getValue());
            }
            HttpRequest request = HttpRequest.newBuilder(apiBase.resolve(path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES || response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("upstream response unavailable");
                }
                JsonObject body = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                int errcode = body.has("errcode") ? body.get("errcode").getAsInt() : -1;
                if (errcode != 0) {
                    throw new IOException("upstream rejected request");
                }
                return body;
            }
        } catch (Exception exception) {
            throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游服务暂时不可用", exception);
        }
    }

    private static long deadline(Duration timeout) throws WeComAuthorizationException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游服务暂时不可用");
        }
        return System.nanoTime() + timeout.toNanos();
    }

    private static Duration remaining(long deadline) throws WeComAuthorizationException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游服务暂时不可用");
        }
        return Duration.ofNanos(remaining);
    }

    private static String successString(JsonObject body, String field) throws WeComAuthorizationException {
        String value = string(body, field);
        if (value.isBlank() || value.length() > 4096) {
            throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503, "企业微信上游响应缺少凭证");
        }
        return value;
    }

    private static int positiveInt(JsonObject body, String field, int fallback) {
        return body.has(field) && body.get(field).isJsonPrimitive() ? Math.max(1, body.get(field).getAsInt()) : fallback;
    }

    private static String string(JsonObject object, String field) {
        return object.has(field) && object.get(field).isJsonPrimitive() ? object.get(field).getAsString() : "";
    }

    private static void require(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " is required and must not exceed " + max + " characters");
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record PermanentCodeResponse(String authCorpId, String permanentCode) {}
    public record AuthorizationInfo(String authCorpId, List<AuthorizedAgent> agents) {}
    public record AuthorizedAgent(String agentId) {}
    public record CorpTokenResponse(String accessToken, int expiresIn) {}
    private record SuiteTicket(String value, Instant expiresAt) {}
    private record SuiteToken(String token, long expiresAtEpochSecond) {}
}

interface WeComAuthorizationClient {
    void acceptSuiteTicket(String suiteId, String suiteTicket, Instant receivedAt)
            throws WeComAuthorizationException;

    WeComAuthorizationGateway.PermanentCodeResponse getPermanentCode(String authCode)
            throws WeComAuthorizationException;

    WeComAuthorizationGateway.AuthorizationInfo getAuthInfo(String authCorpId, String permanentCode)
            throws WeComAuthorizationException;
}
