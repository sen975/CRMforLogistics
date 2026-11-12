package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.gateway.pop.exception.PopClientException;
import com.aliyun.sdk.gateway.pop.exception.PopServerException;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.AddChatappPhoneNumberRequest;
import com.aliyun.sdk.service.cams20200606.models.AddChatappPhoneNumberResponse;
import com.aliyun.sdk.service.cams20200606.models.AddChatappPhoneNumberResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ChatappVerifyAndRegisterRequest;
import com.aliyun.sdk.service.cams20200606.models.ChatappVerifyAndRegisterResponse;
import com.aliyun.sdk.service.cams20200606.models.ChatappVerifyAndRegisterResponseBody;
import com.aliyun.sdk.service.cams20200606.models.GetChatappVerifyCodeRequest;
import com.aliyun.sdk.service.cams20200606.models.GetChatappVerifyCodeResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappVerifyCodeResponseBody;
import com.aliyun.sdk.service.cams20200606.models.GetPhoneNumberVerificationStatusRequest;
import com.aliyun.sdk.service.cams20200606.models.GetPhoneNumberVerificationStatusResponse;
import com.aliyun.sdk.service.cams20200606.models.GetPhoneNumberVerificationStatusResponseBody;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappBindWabaRequest;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappBindWabaResponse;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappBindWabaResponseBody;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappPhoneNumbersRequest;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappPhoneNumbersResponse;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappPhoneNumbersResponseBody;
import com.crmforlogistics.messagecenter.dto.response.ChatAppCapabilityReport;
import darabonba.core.client.ClientOverrideConfiguration;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;

@Component
public class ChatAppCapabilityProbe {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_CODE = 128;
    private static final int MAX_MESSAGE = 1000;

    private final Function<ChatAppAccountCredentials, AsyncClient> clientFactory;

    public ChatAppCapabilityProbe() {
        this(ChatAppCapabilityProbe::createClient);
    }

    ChatAppCapabilityProbe(Function<ChatAppAccountCredentials, AsyncClient> clientFactory) {
        this.clientFactory = clientFactory;
    }

    public ChatAppCapabilityReport probeReadOnly(ChatAppAccountCredentials credentials, String phoneNumber) {
        try (AsyncClient client = clientFactory.apply(credentials)) {
            Action bind = action("QueryChatappBindWaba", credentials, phoneNumber, null, () -> {
                QueryChatappBindWabaResponse response = await(client.queryChatappBindWaba(
                        QueryChatappBindWabaRequest.builder().custSpaceId(credentials.custSpaceId()).build()));
                QueryChatappBindWabaResponseBody body = response == null ? null : response.getBody();
                return provider(body == null ? null : body.getSuccess(), response == null ? null : response.getStatusCode(),
                        body == null ? null : body.getCode(), body == null ? null : body.getMessage(),
                        body == null ? null : body.getRequestId());
            });
            Action phones = action("QueryChatappPhoneNumbers", credentials, phoneNumber, null, () -> {
                QueryChatappPhoneNumbersResponse response = await(client.queryChatappPhoneNumbers(
                        QueryChatappPhoneNumbersRequest.builder().custSpaceId(credentials.custSpaceId()).build()));
                QueryChatappPhoneNumbersResponseBody body = response == null ? null : response.getBody();
                return provider(body == null ? null : body.getSuccess(), response == null ? null : response.getStatusCode(),
                        body == null ? null : body.getCode(), body == null ? null : body.getMessage(),
                        body == null ? null : body.getRequestId());
            });
            Action verification = action("GetPhoneNumberVerificationStatus", credentials, phoneNumber, null, () -> {
                GetPhoneNumberVerificationStatusResponse response = await(client.getPhoneNumberVerificationStatus(
                        GetPhoneNumberVerificationStatusRequest.builder().custSpaceId(credentials.custSpaceId())
                                .phoneNumber(required(phoneNumber, "phoneNumber")).build()));
                GetPhoneNumberVerificationStatusResponseBody body = response == null ? null : response.getBody();
                return provider(null, response == null ? null : response.getStatusCode(),
                        body == null ? null : body.getCode(), body == null ? null : body.getMessage(),
                        body == null ? null : body.getRequestId());
            });
            return report("READ_ONLY", List.of(bind, phones, verification));
        }
    }

    public ChatAppCapabilityReport addTestNumber(ChatAppAccountCredentials credentials, AddNumberCommand command) {
        try (AsyncClient client = clientFactory.apply(credentials)) {
            Action result = action("AddChatappPhoneNumber", credentials, command.phoneNumber(), null, () -> {
                AddChatappPhoneNumberResponse response = await(client.addChatappPhoneNumber(
                        AddChatappPhoneNumberRequest.builder().cc(required(command.countryCode(), "countryCode"))
                                .custSpaceId(credentials.custSpaceId())
                                .phoneNumber(required(command.phoneNumber(), "phoneNumber"))
                                .verifiedName(required(command.verifiedName(), "verifiedName")).build()));
                AddChatappPhoneNumberResponseBody body = response == null ? null : response.getBody();
                return provider(null, response == null ? null : response.getStatusCode(),
                        body == null ? null : body.getCode(), body == null ? null : body.getMessage(),
                        body == null ? null : body.getRequestId());
            });
            return report("PROVISIONING_TEST", List.of(result));
        }
    }

    public ChatAppCapabilityReport sendVerificationCode(ChatAppAccountCredentials credentials, SendCodeCommand command) {
        try (AsyncClient client = clientFactory.apply(credentials)) {
            Action result = action("GetChatappVerifyCode", credentials, command.phoneNumber(), null, () -> {
                GetChatappVerifyCodeResponse response = await(client.getChatappVerifyCode(
                        GetChatappVerifyCodeRequest.builder().custSpaceId(credentials.custSpaceId())
                                .phoneNumber(required(command.phoneNumber(), "phoneNumber"))
                                .locale(required(command.locale(), "locale"))
                                .method(required(command.method(), "method")).build()));
                GetChatappVerifyCodeResponseBody body = response == null ? null : response.getBody();
                return provider(null, response == null ? null : response.getStatusCode(),
                        body == null ? null : body.getCode(), body == null ? null : body.getMessage(),
                        body == null ? null : body.getRequestId());
            });
            return report("PROVISIONING_TEST", List.of(result));
        }
    }

    public ChatAppCapabilityReport verifyAndRegister(ChatAppAccountCredentials credentials, VerifyCommand command) {
        try (AsyncClient client = clientFactory.apply(credentials)) {
            Action result = action("ChatappVerifyAndRegister", credentials, command.phoneNumber(),
                    command.verificationCode(), () -> {
                        ChatappVerifyAndRegisterResponse response = await(client.chatappVerifyAndRegister(
                                ChatappVerifyAndRegisterRequest.builder().custSpaceId(credentials.custSpaceId())
                                        .phoneNumber(required(command.phoneNumber(), "phoneNumber"))
                                        .verifyCode(required(command.verificationCode(), "verificationCode")).build()));
                        ChatappVerifyAndRegisterResponseBody body = response == null ? null : response.getBody();
                        return provider(null, response == null ? null : response.getStatusCode(),
                                body == null ? null : body.getCode(), body == null ? null : body.getMessage(),
                                body == null ? null : body.getRequestId());
                    });
            return report("PROVISIONING_TEST", List.of(result));
        }
    }

    public ChatAppCapabilityReport migrationReview() {
        Instant now = Instant.now();
        List<ChatAppCapabilityReport.ActionResult> results = List.of(
                unverified("CreateChatappMigrationInitiate"),
                unverified("ChatappMigrationVerified"),
                unverified("ChatappMigrationRegister"));
        return new ChatAppCapabilityReport(UUID.randomUUID(), null, null,
                "MIGRATION_REVIEW", false, now, results);
    }

    private Action action(String name, ChatAppAccountCredentials credentials, String phoneNumber,
                          String verificationCode, ProviderCall call) {
        try {
            ProviderResult result = call.call();
            boolean success = result.success();
            return new Action(new ChatAppCapabilityReport.ActionResult(name,
                    success ? "VERIFIED" : "FAILED", bounded(result.requestId(), 255),
                    bounded(result.code(), MAX_CODE), sanitize(result.message(), credentials, phoneNumber,
                    verificationCode)), success);
        } catch (Exception exception) {
            Throwable root = root(exception);
            return new Action(new ChatAppCapabilityReport.ActionResult(name, "FAILED",
                    bounded(requestId(root), 255), bounded(errorCode(root), MAX_CODE),
                    sanitize(root.getMessage(), credentials, phoneNumber, verificationCode)), false);
        }
    }

    private static ProviderResult provider(Boolean success, Integer httpStatus, String code,
                                           String message, String requestId) {
        boolean ok = Boolean.TRUE.equals(success)
                || ((httpStatus == null || httpStatus >= 200 && httpStatus < 300) && "OK".equalsIgnoreCase(value(code)));
        return new ProviderResult(ok, code, message, requestId);
    }

    private static ChatAppCapabilityReport report(String phase, List<Action> actions) {
        List<ChatAppCapabilityReport.ActionResult> results = actions.stream().map(Action::result).toList();
        return new ChatAppCapabilityReport(UUID.randomUUID(), null, null, phase,
                actions.stream().allMatch(Action::success), Instant.now(), results);
    }

    private static ChatAppCapabilityReport.ActionResult unverified(String action) {
        return new ChatAppCapabilityReport.ActionResult(action, "UNVERIFIED_FOR_PRODUCTION", "", "",
                "Production migration was not invoked");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static AsyncClient createClient(ChatAppAccountCredentials credentials) {
        return AsyncClient.builder().region(credentials.region())
                .credentialsProvider(StaticCredentialProvider.create(Credential.builder()
                        .accessKeyId(credentials.accessKeyId())
                        .accessKeySecret(credentials.accessKeySecret()).build()))
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(credentials.endpoint()))
                .build();
    }

    private static String sanitize(String message, ChatAppAccountCredentials credentials,
                                   String phoneNumber, String verificationCode) {
        String sanitized = value(message);
        sanitized = replace(sanitized, credentials.accessKeyId(), "[REDACTED]");
        sanitized = replace(sanitized, credentials.accessKeySecret(), "[REDACTED]");
        sanitized = replace(sanitized, verificationCode, "[REDACTED]");
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            sanitized = replace(sanitized, phoneNumber, "***" + last4(phoneNumber));
        }
        return bounded(sanitized, MAX_MESSAGE);
    }

    private static String replace(String source, String secret, String replacement) {
        return secret == null || secret.isBlank() ? source : source.replace(secret, replacement);
    }

    private static String errorCode(Throwable error) {
        if (error instanceof PopClientException client) return value(client.getErrCode());
        if (error instanceof PopServerException server) return value(server.getErrCode());
        return error == null ? "UNKNOWN" : error.getClass().getSimpleName();
    }

    private static String requestId(Throwable error) {
        if (error instanceof PopClientException client) return value(client.getRequestId());
        if (error instanceof PopServerException server) return value(server.getRequestId());
        return "";
    }

    private static Throwable root(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) current = current.getCause();
        return current == null ? new IllegalStateException("Unknown provider error") : current;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String last4(String phoneNumber) {
        String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
        return digits.substring(Math.max(0, digits.length() - 4));
    }

    private static String bounded(String value, int max) {
        String normalized = value(value);
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    public record AddNumberCommand(String countryCode, String phoneNumber, String verifiedName) { }
    public record SendCodeCommand(String phoneNumber, String locale, String method) { }
    public record VerifyCommand(String phoneNumber, String verificationCode) { }
    private record ProviderResult(boolean success, String code, String message, String requestId) { }
    private record Action(ChatAppCapabilityReport.ActionResult result, boolean success) { }

    @FunctionalInterface
    private interface ProviderCall {
        ProviderResult call() throws Exception;
    }
}
