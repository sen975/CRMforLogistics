package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.service.wecom.WeComAccessTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComApiClient {
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Pattern SAFE_PATH = Pattern.compile("/cgi-bin/[a-z0-9_/-]+");
    private static final Pattern HINT = Pattern.compile("hint:\\s*\\[?([A-Za-z0-9_-]{1,128})]?", Pattern.CASE_INSENSITIVE);
    private static final Set<Integer> TOKEN_ERRORS = Set.of(40001, 40014, 42001);
    private static final Set<Integer> PERMISSION_ERRORS = Set.of(48002, 50001, 60011, 84024);

    private final ObjectMapper objectMapper;
    private final WeComAccessTokenService tokens;
    private final WeComRestClientFactory restClients;

    public WeComApiClient(ObjectMapper objectMapper,
                          WeComAccessTokenService tokens,
                          WeComRestClientFactory restClients) {
        this.objectMapper = objectMapper;
        this.tokens = tokens;
        this.restClients = restClients;
    }

    public JsonNode get(ResolvedInstallation installation, String path,
                        Map<String, ?> query, Duration timeout) {
        return execute(installation, HttpMethod.GET, path, query, null, timeout);
    }

    public JsonNode post(ResolvedInstallation installation, String path,
                         Object request, Duration timeout) {
        return execute(installation, HttpMethod.POST, path, Map.of(), request, timeout);
    }

    private JsonNode execute(ResolvedInstallation installation, HttpMethod method, String path,
                             Map<String, ?> query, Object body, Duration timeout) {
        validate(path, query, timeout);
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = tokens.accessToken(installation, timeout);
            try {
                RestClient.RequestBodySpec request = restClients.create(timeout).method(method)
                        .uri(builder -> {
                            builder.path(path).queryParam("access_token", token);
                            query.forEach((name, value) -> {
                                if (value != null && !value.toString().isBlank()) {
                                    builder.queryParam(name, value);
                                }
                            });
                            return builder.build();
                        })
                        .accept(MediaType.APPLICATION_JSON);
                if (body != null) {
                    request.contentType(MediaType.APPLICATION_JSON).body(body);
                }
                return request.exchange((ignored, response) -> parse(response.getStatusCode().value(),
                        response.getHeaders().getContentLength(), response.getBody(), path));
            } catch (TokenExpiredException expired) {
                if (attempt == 0) {
                    tokens.invalidate(installation);
                    continue;
                }
                throw upstreamError(path, expired.errcode(), expired.errmsg());
            } catch (WeComException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                        "企业微信上游服务暂时不可用", null, path, null, null);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private JsonNode parse(int httpStatus, long contentLength, InputStream input, String path) {
        if (contentLength > MAX_RESPONSE_BYTES) {
            throw responseTooLarge(path, httpStatus);
        }
        try {
            byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw responseTooLarge(path, httpStatus);
            }
            if (httpStatus < 200 || httpStatus >= 300) {
                throw new WeComException("WECOM_UPSTREAM_HTTP_ERROR", 502,
                        "企业微信上游请求失败", null, path, httpStatus, null);
            }
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                throw invalidResponse(path, httpStatus);
            }
            int errcode = root.path("errcode").asInt(-1);
            String errmsg = root.path("errmsg").asText("");
            if (TOKEN_ERRORS.contains(errcode)) {
                throw new TokenExpiredException(errcode, errmsg);
            }
            if (errcode != 0) {
                throw upstreamError(path, errcode, errmsg);
            }
            ObjectNode data = ((ObjectNode) root).deepCopy();
            data.remove("errcode");
            data.remove("errmsg");
            return data;
        } catch (WeComException | TokenExpiredException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse(path, httpStatus);
        }
    }

    private static WeComException upstreamError(String path, int errcode, String errmsg) {
        int status = PERMISSION_ERRORS.contains(errcode) ? 403 : 502;
        String code = status == 403 ? "WECOM_API_PERMISSION_DENIED" : "WECOM_API_UPSTREAM_ERROR";
        return new WeComException(code, status,
                status == 403 ? "企业微信应用缺少所需权限" : "企业微信上游返回错误",
                errcode, path, 200, safeHint(errmsg));
    }

    private static WeComException invalidResponse(String path, Integer httpStatus) {
        return new WeComException("WECOM_UPSTREAM_RESPONSE_INVALID", 502,
                "企业微信上游响应格式无效", null, path, httpStatus, null);
    }

    private static WeComException responseTooLarge(String path, Integer httpStatus) {
        return new WeComException("WECOM_UPSTREAM_RESPONSE_TOO_LARGE", 502,
                "企业微信上游响应超过大小限制", null, path, httpStatus, null);
    }

    private static String safeHint(String errmsg) {
        if (errmsg == null) return null;
        Matcher matcher = HINT.matcher(errmsg);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void validate(String path, Map<String, ?> query, Duration timeout) {
        if (path == null || !SAFE_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("WeCom API path is invalid");
        }
        if (query == null || query.size() > 16 || query.keySet().stream()
                .anyMatch(name -> name == null || !name.matches("[a-z_]{1,64}"))) {
            throw new IllegalArgumentException("WeCom API query is invalid");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("WeCom API timeout is invalid");
        }
    }

    private static final class TokenExpiredException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int errcode;
        private final String errmsg;

        private TokenExpiredException(int errcode, String errmsg) {
            super("WeCom access token expired", null, false, false);
            this.errcode = errcode;
            this.errmsg = errmsg;
        }

        private int errcode() {
            return errcode;
        }

        private String errmsg() {
            return errmsg;
        }
    }
}
