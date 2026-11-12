package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded HTTP gateway for service-provider WeCom credentials. */
public final class WeComAuthorizationGateway implements WeComAuthorizationClient {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern UPSTREAM_HINT = Pattern.compile(
            "(?i)(?:^|\\s)hint\\s*:\\s*\\[([A-Za-z0-9_-]{1,128})]");
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
        if (!isKnownSuite(suiteId)) {
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
        String path = "/cgi-bin/service/get_suite_token";
        JsonObject body = postJson(path, Map.of(
                "suite_id", suiteId,
                "suite_secret", suiteSecret(suiteId),
                "suite_ticket", ticket.value()), remaining(deadline));
        String token = successString(body, "suite_access_token", path);
        int expiresIn = positiveInt(body, "expires_in", 7200);
        suiteTokens.put(suiteId, new SuiteToken(token, now + expiresIn));
        return token;
    }

    public PermanentCodeResponse getPermanentCode(String authCode) throws WeComAuthorizationException {
        require(authCode, "authCode", 512);
        String path = "/cgi-bin/service/v2/get_permanent_code";
        JsonObject body = postJson(path + "?suite_access_token="
                + encode(suiteAccessToken(config.wecomSuiteId())), Map.of("auth_code", authCode));
        String permanentCode = successString(body, "permanent_code", path);
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
        JsonObject authCorpInfo = body.has("auth_corp_info") && body.get("auth_corp_info").isJsonObject()
                ? body.getAsJsonObject("auth_corp_info") : new JsonObject();
        String corpId = string(authCorpInfo, "corpid");
        JsonObject authInfo = body.has("auth_info") && body.get("auth_info").isJsonObject()
                ? body.getAsJsonObject("auth_info") : new JsonObject();
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
        String path = "/cgi-bin/service/get_corp_token";
        JsonObject body = postJson(path + "?suite_access_token="
                + encode(suiteAccessToken(config.wecomSuiteId(), remaining(deadline))), Map.of(
                "auth_corpid", authCorpId,
                "permanent_code", permanentCode), remaining(deadline));
        return new CorpTokenResponse(successString(body, "access_token", path),
                positiveInt(body, "expires_in", 7200));
    }

    public CorpTokenResponse getDevelopedAppToken(String authCorpId, String developedAppSecret,
                                                   Duration timeout)
            throws WeComAuthorizationException {
        require(authCorpId, "authCorpId", 128);
        require(developedAppSecret, "developedAppSecret", 512);
        long deadline = deadline(timeout);
        String path = "/cgi-bin/gettoken";
        JsonObject body = getJson(path + "?corpid=" + encode(authCorpId)
                + "&corpsecret=" + encode(developedAppSecret), remaining(deadline));
        return new CorpTokenResponse(successString(body, "access_token", path),
                positiveInt(body, "expires_in", 7200));
    }

    public LoginIdentity getLoginIdentity(String code) throws WeComAuthorizationException {
        return getLoginIdentity(code, TIMEOUT);
    }

    public LoginIdentity getLoginIdentity(String code, Duration timeout) throws WeComAuthorizationException {
        require(code, "code", 512);
        if (!config.hasCompleteWeComLoginSuiteConfiguration()) {
            throw new WeComAuthorizationException("WECOM_LOGIN_SUITE_NOT_CONFIGURED", 503,
                    "企业微信登录授权 Suite 尚未配置");
        }
        long deadline = deadline(timeout);
        String path = "/cgi-bin/service/auth/getuserinfo3rd?suite_access_token="
                + encode(suiteAccessToken(config.wecomLoginSuiteId(), remaining(deadline))) + "&code=" + encode(code);
        JsonObject body = getJson(path, remaining(deadline));
        String corpId = string(body, "corpid");
        String userId = string(body, "userid");
        if (corpId.isBlank() || userId.isBlank()) {
            throw new WeComAuthorizationException("WECOM_LOGIN_IDENTITY_UNAVAILABLE", 403,
                    "企业微信登录未返回企业成员身份");
        }
        require(corpId, "corpId", 128);
        require(userId, "userId", 128);
        return new LoginIdentity(corpId, userId);
    }

    private boolean isKnownSuite(String suiteId) {
        return suiteId.equals(config.wecomSuiteId())
                || (!config.wecomLoginSuiteId().isBlank() && suiteId.equals(config.wecomLoginSuiteId()));
    }

    private String suiteSecret(String suiteId) throws WeComAuthorizationException {
        if (suiteId.equals(config.wecomSuiteId())) return config.wecomSuiteSecret();
        if (!config.wecomLoginSuiteId().isBlank() && suiteId.equals(config.wecomLoginSuiteId())) {
            return config.wecomLoginSuiteSecret();
        }
        throw new WeComAuthorizationException("WECOM_CALLBACK_SUITE_MISMATCH", 403,
                "企业微信 SuiteId 不匹配");
    }

    private JsonObject getJson(String path, Duration timeout) throws WeComAuthorizationException {
        try {
            long requestDeadline = deadline(timeout);
            HttpRequest request = HttpRequest.newBuilder(apiBase.resolve(path))
                    .timeout(remaining(requestDeadline))
                    .GET()
                    .build();
            return sendJson(request, requestDeadline);
        } catch (Exception exception) {
            if (exception instanceof WeComAuthorizationException authorizationException) {
                throw authorizationException;
            }
            if (exception instanceof UpstreamResponseException upstream) {
                throw upstreamUnavailable(upstream);
            }
            throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游服务暂时不可用", exception);
        }
    }

    private JsonObject postJson(String path, Map<String, String> values) throws WeComAuthorizationException {
        return postJson(path, values, TIMEOUT);
    }

    private JsonObject postJson(String path, Map<String, String> values, Duration timeout)
            throws WeComAuthorizationException {
        try {
            long requestDeadline = deadline(timeout);
            JsonObject json = new JsonObject();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                json.addProperty(entry.getKey(), entry.getValue());
            }
            HttpRequest request = HttpRequest.newBuilder(apiBase.resolve(path))
                    .timeout(remaining(requestDeadline))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                    .build();
            return sendJson(request, requestDeadline);
        } catch (Exception exception) {
            if (exception instanceof UpstreamResponseException upstream) {
                throw upstreamUnavailable(upstream);
            }
            throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游服务暂时不可用", exception);
        }
    }

    private static WeComAuthorizationException upstreamUnavailable(UpstreamResponseException failure) {
        return new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                "企业微信上游服务暂时不可用", failure.errcode(), failure.path(),
                failure.statusCode(), failure.hint(), failure);
    }

    private JsonObject sendJson(HttpRequest request, long requestDeadline) throws Exception {
        CompletableFuture<HttpResponse<byte[]>> responseFuture = client.sendAsync(
                request, ignored -> new BoundedBodySubscriber(MAX_RESPONSE_BYTES));
        final HttpResponse<byte[]> response;
        try {
            response = responseFuture.get(remaining(requestDeadline).toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            responseFuture.cancel(true);
            throw new IOException("upstream response unavailable", exception);
        } catch (InterruptedException exception) {
            responseFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("upstream response unavailable", exception);
        }
        byte[] bytes = response.body();
        if (bytes == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new UpstreamResponseException(request.uri().getPath(), response.statusCode(), null, null);
        }
        JsonObject body = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        Integer errcode = body.has("errcode") && body.get("errcode").isJsonPrimitive()
                ? body.get("errcode").getAsInt() : null;
        if (errcode != null && errcode != 0) {
            throw new UpstreamResponseException(request.uri().getPath(), response.statusCode(), errcode,
                    extractHint(string(body, "errmsg")));
        }
        return body;
    }

    private static String extractHint(String errmsg) {
        if (errmsg == null || errmsg.isBlank() || errmsg.length() > 4096) return null;
        Matcher matcher = UPSTREAM_HINT.matcher(errmsg);
        return matcher.find() ? matcher.group(1) : null;
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

    private static String successString(JsonObject body, String field, String upstreamPath)
            throws WeComAuthorizationException {
        String value = string(body, field);
        if (value.isBlank() || value.length() > 4096) {
            throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游响应缺少凭证", null, upstreamPath, 200, null);
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

    private static final class UpstreamResponseException extends IOException {
        private final String path;
        private final int statusCode;
        private final Integer errcode;
        private final String hint;

        private UpstreamResponseException(String path, int statusCode, Integer errcode, String hint) {
            super("upstream response rejected");
            this.path = path;
            this.statusCode = statusCode;
            this.errcode = errcode;
            this.hint = hint;
        }

        private String path() {
            return path;
        }

        private Integer errcode() {
            return errcode;
        }

        private int statusCode() {
            return statusCode;
        }

        private String hint() {
            return hint;
        }
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximumBytes;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private int size;

        private BoundedBodySubscriber(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            try {
                for (ByteBuffer item : items) {
                    int length = item.remaining();
                    if ((long) size + length > maximumBytes) {
                        subscription.cancel();
                        body.completeExceptionally(new IOException("upstream response too large"));
                        return;
                    }
                    byte[] chunk = new byte[length];
                    item.get(chunk);
                    bytes.writeBytes(chunk);
                    size += length;
                }
                subscription.request(1);
            } catch (RuntimeException exception) {
                subscription.cancel();
                body.completeExceptionally(exception);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }

    public record PermanentCodeResponse(String authCorpId, String permanentCode) {}
    public record AuthorizationInfo(String authCorpId, List<AuthorizedAgent> agents) {}
    public record AuthorizedAgent(String agentId) {}
    public record CorpTokenResponse(String accessToken, int expiresIn) {}
    public record LoginIdentity(String corpId, String userId) {}
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
