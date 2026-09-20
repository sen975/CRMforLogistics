package com.crmforlogistics.messagecenter.channel.chatapp.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsException;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsResolver;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import com.aliyun.teaopenapi.Client;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teaopenapi.models.OpenApiRequest;
import com.aliyun.teaopenapi.models.Params;
import com.aliyun.teautil.models.RuntimeOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.*;
import com.crmforlogistics.messagecenter.service.whatsapp.template.ChatAppPublicTemplateGateway;

@Service
public class AliyunChatAppPublicTemplateGateway implements ChatAppPublicTemplateGateway {
    private static final String API_VERSION = "2020-06-06";
    private static final String DEFAULT_REGION = "ap-southeast-1";
    private static final String DEFAULT_ENDPOINT = "cams.ap-southeast-1.aliyuncs.com";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper;
    private final ChannelAccountMapper accountMapper;
    private final ChatAppAccountCredentialsResolver credentialsResolver;
    private final Client fixedClient;
    private final String fixedCustSpaceId;
    private final Duration timeout;

    @Autowired
    public AliyunChatAppPublicTemplateGateway(ObjectMapper objectMapper,
                                              ChannelAccountMapper accountMapper,
                                              ChatAppAccountCredentialsResolver credentialsResolver) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.accountMapper = Objects.requireNonNull(accountMapper);
        this.credentialsResolver = Objects.requireNonNull(credentialsResolver);
        this.fixedClient = null;
        this.fixedCustSpaceId = null;
        this.timeout = DEFAULT_TIMEOUT;
    }

    AliyunChatAppPublicTemplateGateway(ObjectMapper objectMapper, Client client,
                                       Duration timeout, String custSpaceId) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.accountMapper = null;
        this.credentialsResolver = null;
        this.fixedClient = Objects.requireNonNull(client);
        this.fixedCustSpaceId = required(custSpaceId, "custSpaceId");
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
    }

    @Override
    public Page list(UUID accountId, Query query) {
        try {
            ChatAppAccountCredentials credentials = fixedClient == null ? credentialsFor(accountId) : null;
            String custSpaceId = fixedClient == null ? credentials.custSpaceId() : fixedCustSpaceId;
            Client client = fixedClient == null ? createClient(credentials) : fixedClient;
            Map<String, String> request = listQuery(query, custSpaceId, objectMapper);
            Map<String, ?> response = client.callApi(params("ListBaseTemplate"),
                    new OpenApiRequest().setQuery(request), runtime());
            return PublicTemplateResponseParser.parse(body(response), query.page(), query.size());
        } catch (WhatsAppTemplateException e) {
            throw e;
        } catch (Exception e) {
            throw providerFailure("PUBLIC_TEMPLATE_LIST_FAILED", e);
        }
    }

    static Map<String, String> listQuery(Query query, String custSpaceId) {
        return listQuery(query, custSpaceId, new ObjectMapper());
    }

    private static Map<String, String> listQuery(Query query, String custSpaceId, ObjectMapper objectMapper) {
        try {
            Objects.requireNonNull(query, "query is required");
            Map<String, String> result = new LinkedHashMap<>();
            result.put("CustSpaceId", required(custSpaceId, "custSpaceId"));
            result.put("Name", nullToEmpty(query.name()));
            result.put("Language", nullToEmpty(query.language()));
            result.put("UseType", "private");
            result.put("MessageCategory", "WHATSAPP");
            result.put("Page.Size", String.valueOf(query.size()));
            result.put("Page.Index", String.valueOf(query.page()));
            result.put("Category", nullToEmpty(query.category()));
            result.put("Industries", objectMapper.writeValueAsString(query.industries()));
            result.put("Usecases", objectMapper.writeValueAsString(query.usecases()));
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("PUBLIC_TEMPLATE_REQUEST_INVALID", e);
        }
    }

    private ChatAppAccountCredentials credentialsFor(UUID accountId) {
        ChannelAccountEntity account = accountId == null || accountMapper == null
                ? null : accountMapper.selectById(accountId);
        if (account == null || account.getDeletedAt() != null
                || !("chatapp".equalsIgnoreCase(account.getChannelType())
                || "whatsapp".equalsIgnoreCase(account.getChannelType()))
                || !"active".equalsIgnoreCase(account.getAuthStatus())) {
            throw new ChatAppAccountCredentialsException("CHATAPP_ACCOUNT_CREDENTIALS_MISSING");
        }
        return credentialsResolver.resolve(account);
    }

    private static Params params(String action) {
        if (!"ListBaseTemplate".equals(action)) {
            throw new IllegalArgumentException("Unsupported public template action: " + action);
        }
        return new Params().setAction(action).setVersion(API_VERSION).setProtocol("HTTPS")
                .setPathname("/").setMethod("GET")
                .setAuthType("AK").setBodyType("json").setReqBodyType("json").setStyle("RPC");
    }

    private RuntimeOptions runtime() {
        int millis = Math.toIntExact(Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        return new RuntimeOptions().setReadTimeout(millis).setConnectTimeout(millis).setMaxAttempts(1);
    }

    private static Client createClient(ChatAppAccountCredentials credentials) {
        String region = credentials.region() == null || credentials.region().isBlank()
                ? DEFAULT_REGION : credentials.region().trim();
        String endpoint = credentials.endpoint() == null || credentials.endpoint().isBlank()
                ? "cams." + region + ".aliyuncs.com" : credentials.endpoint().trim();
        try {
            Config sdk = new Config().setAccessKeyId(required(credentials.accessKeyId(), "accessKeyId"))
                    .setAccessKeySecret(required(credentials.accessKeySecret(), "accessKeySecret")).setRegionId(region)
                    .setEndpoint(endpoint).setProtocol("https");
            return new Client(sdk);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize official CAMS OpenAPI client", e);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static Map<String, Object> body(Map<String, ?> response) {
        if (response == null) return Map.of();
        Object body = response.get("body");
        Object candidate = body instanceof Map<?, ?> ? body : response;
        if (!(candidate instanceof Map<?, ?> values)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() instanceof String key) result.put(key, entry.getValue());
        }
        return result;
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }

    private static WhatsAppTemplateException providerFailure(String code, Exception cause) {
        ChatAppAccountCredentialsException credentialsError = credentialsError(cause);
        if (credentialsError != null) {
            return new WhatsAppTemplateException(credentialsError.code(), org.springframework.http.HttpStatus.CONFLICT,
                    credentialsError.code(), Map.of(), null, false);
        }
        TeaException providerError = teaException(cause);
        String providerCode = providerError == null ? null : bounded(providerError.getCode(), 128);
        String providerRequestId = providerError == null ? null : providerRequestId(providerError.getData());
        Integer providerStatus = providerError == null ? null : providerStatus(providerError);
        String diagnostic = providerCode;
        if (diagnostic == null && providerStatus != null) {
            diagnostic = "HTTP_" + providerStatus;
        }
        if (diagnostic == null) diagnostic = bounded(rootCause(cause).getClass().getSimpleName(), 128);
        boolean deterministicProviderRejection = providerError != null
                && ((providerStatus != null && providerStatus >= 400 && providerStatus < 500)
                    || (providerStatus == null && providerCode != null));
        boolean retryable = !deterministicProviderRejection;
        String message = "Public template list failed";
        if (diagnostic != null) message += ": " + diagnostic;
        return new WhatsAppTemplateException(code, org.springframework.http.HttpStatus.BAD_GATEWAY,
                message, Map.of(), providerRequestId, retryable);
    }

    private static ChatAppAccountCredentialsException credentialsError(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof ChatAppAccountCredentialsException credentialsException) {
                return credentialsException;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return null;
    }

    private static TeaException teaException(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof TeaException teaException) return teaException;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return null;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current.getCause() != null && current.getCause() != current && depth < 16; depth++) {
            current = current.getCause();
        }
        return current;
    }

    private static String providerRequestId(Map<String, Object> data) {
        if (data == null) return null;
        for (String key : List.of("RequestId", "requestId", "requestid", "request_id")) {
            String value = bounded(text(data.get(key)), 512);
            if (value != null) return value;
        }
        return null;
    }

    private static Integer providerStatus(TeaException error) {
        if (error.getStatusCode() != null) return error.getStatusCode();
        Map<String, Object> data = error.getData();
        if (data == null) return null;
        for (String key : List.of("statusCode", "StatusCode", "httpStatus", "HttpStatus")) {
            Object value = data.get(key);
            if (value instanceof Number number) return number.intValue();
            if (value instanceof String text) {
                try {
                    return Integer.valueOf(text);
                } catch (NumberFormatException ignored) {
                    // Continue searching the bounded provider metadata keys.
                }
            }
        }
        return null;
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value) || value.length() > maxLength) {
            return null;
        }
        return value.trim();
    }
}
