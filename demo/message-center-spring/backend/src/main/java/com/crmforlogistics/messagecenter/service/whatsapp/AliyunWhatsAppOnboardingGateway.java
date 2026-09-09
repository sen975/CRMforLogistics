package com.crmforlogistics.messagecenter.service.whatsapp;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.ChatappSyncPhoneNumberRequest;
import com.aliyun.sdk.service.cams20200606.models.ChatappSyncPhoneNumberResponse;
import com.aliyun.sdk.service.cams20200606.models.ChatappSyncPhoneNumberResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ChatappBindWabaRequest;
import com.aliyun.sdk.service.cams20200606.models.ChatappBindWabaResponse;
import com.aliyun.sdk.service.cams20200606.models.ChatappBindWabaResponseBody;
import com.aliyun.sdk.service.cams20200606.models.GetChatappVerifyCodeRequest;
import com.aliyun.sdk.service.cams20200606.models.GetChatappVerifyCodeResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappVerifyCodeResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ChatappVerifyAndRegisterRequest;
import com.aliyun.sdk.service.cams20200606.models.ChatappVerifyAndRegisterResponse;
import com.aliyun.sdk.service.cams20200606.models.ChatappVerifyAndRegisterResponseBody;
import com.aliyun.sdk.service.cams20200606.models.AddChatappPhoneNumberRequest;
import com.aliyun.sdk.service.cams20200606.models.AddChatappPhoneNumberResponse;
import com.aliyun.sdk.service.cams20200606.models.AddChatappPhoneNumberResponseBody;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappPhoneNumbersRequest;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappPhoneNumbersResponse;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappPhoneNumbersResponseBody;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappPhoneNumbersResponseBody.PhoneNumbers;
import com.aliyun.sdk.service.cams20200606.models.GetPermissionByCodeRequest;
import com.aliyun.sdk.service.cams20200606.models.GetPermissionByCodeResponse;
import com.aliyun.sdk.service.cams20200606.models.GetPermissionByCodeResponseBody;
import com.aliyun.sdk.service.cams20200606.models.IsvGetAppIdRequest;
import com.aliyun.sdk.service.cams20200606.models.IsvGetAppIdResponse;
import com.aliyun.sdk.service.cams20200606.models.IsvGetAppIdResponseBody;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import darabonba.core.client.ClientOverrideConfiguration;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
public class AliyunWhatsAppOnboardingGateway implements WhatsAppOnboardingGateway {
    private static final long TIMEOUT_SECONDS = 20;
    private final AppConfig config;
    private final CredentialCipher credentialCipher;
    private final Function<ProviderCredentials, AsyncClient> clientFactory;

    public AliyunWhatsAppOnboardingGateway(AppConfig config, CredentialCipher credentialCipher) {
        this(config, credentialCipher, AliyunWhatsAppOnboardingGateway::createClient);
    }

    AliyunWhatsAppOnboardingGateway(AppConfig config, CredentialCipher credentialCipher,
                                    Function<ProviderCredentials, AsyncClient> clientFactory) {
        this.config = config;
        this.credentialCipher = credentialCipher;
        this.clientFactory = clientFactory;
    }

    @Override
    public StartupProfile startupProfile(String onboardingMode) {
        String mode = require(onboardingMode);
        try (AsyncClient client = client(globalProviderCredentials())) {
            IsvGetAppIdResponse response = client.isvGetAppId(IsvGetAppIdRequest.builder()
                    .type("whatsapp").intlVersion("2").build()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            IsvGetAppIdResponseBody body = response == null ? null : response.getBody();
            requireSuccess(null, body == null ? null : body.getCode());
            return new StartupProfile(require(body == null ? null : body.getAppId()),
                    require(body == null ? null : body.getConfigId()), mode);
        } catch (WhatsAppAuthorizationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    @Override
    public void verifyEmbeddedCode(String code) {
        try (AsyncClient client = client(globalProviderCredentials())) {
            GetPermissionByCodeResponse response = client.getPermissionByCode(
                    GetPermissionByCodeRequest.builder().code(require(code)).build())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            GetPermissionByCodeResponseBody body = response == null ? null : response.getBody();
            requireSuccess(null, body == null ? null : body.getCode());
        } catch (WhatsAppAuthorizationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    @Override
    public BoundScope bindWaba(String wabaId) {
        try (AsyncClient client = client(globalProviderCredentials())) {
            ChatappBindWabaResponse response = client.chatappBindWaba(
                    ChatappBindWabaRequest.builder().wabaId(require(wabaId)).build())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ChatappBindWabaResponseBody body = response == null ? null : response.getBody();
            requireSuccess(body == null ? null : body.getSuccess(), body == null ? null : body.getCode());
            ChatappBindWabaResponseBody.Data data = body == null ? null : body.getData();
            return new BoundScope(require(data == null ? null : data.getCustSpaceId()),
                    require(data == null ? null : data.getWabaId()));
        } catch (WhatsAppAuthorizationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    @Override
    public List<ProviderPhone> syncPhoneNumbers(WhatsAppProviderScopeEntity scope) {
        String custSpaceId = requireScope(scope);
        try (AsyncClient client = client(credentials(scope))) {
            ChatappSyncPhoneNumberResponse sync = client.chatappSyncPhoneNumber(
                    ChatappSyncPhoneNumberRequest.builder().custSpaceId(custSpaceId).build())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ChatappSyncPhoneNumberResponseBody syncBody = body(sync);
            requireSuccess(syncBody == null ? null : syncBody.getSuccess(),
                    syncBody == null ? null : syncBody.getCode());
            QueryChatappPhoneNumbersResponse response = client.queryChatappPhoneNumbers(
                    QueryChatappPhoneNumbersRequest.builder().custSpaceId(custSpaceId).build())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            QueryChatappPhoneNumbersResponseBody body = body(response);
            requireSuccess(body == null ? null : body.getSuccess(), body == null ? null : body.getCode());
            if (body == null || body.getPhoneNumbers() == null) return List.of();
            return body.getPhoneNumbers().stream()
                    .filter(phone -> !blank(phone.getPhoneNumber()))
                    .map(phone -> new ProviderPhone(normalize(phone.getPhoneNumber()), phone.getVerifiedName(),
                            phoneStatus(phone.getStatus()), verificationStatus(phone.getCodeVerificationStatus())))
                    .toList();
        } catch (WhatsAppAuthorizationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    @Override
    public String encryptedProviderConfig() {
        try {
            return credentialCipher.encrypt(globalCredentials());
        } catch (CredentialCipher.CredentialEncryptionException exception) {
            throw new WhatsAppAuthorizationException("WHATSAPP_PROVIDER_CREDENTIALS_UNAVAILABLE",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public ProviderResult add(AddCommand command, WhatsAppProviderScopeEntity scope) {
        String custSpaceId = requireScope(scope);
        try (AsyncClient client = client(credentials(scope))) {
            AddChatappPhoneNumberResponse response = client.addChatappPhoneNumber(
                    AddChatappPhoneNumberRequest.builder().custSpaceId(custSpaceId)
                            .cc(require(command.countryCode()))
                            .phoneNumber(require(command.phoneNumber()))
                            .verifiedName(require(command.verifiedName())).build())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            AddChatappPhoneNumberResponseBody body = response == null ? null : response.getBody();
            requireSuccess(body == null ? null : body.getSuccess(), body == null ? null : body.getCode());
            return new ProviderResult(custSpaceId, scope.getWabaId(), normalize(command.phoneNumber()),
                    "PENDING", "PENDING", command.verifiedName());
        } catch (WhatsAppAuthorizationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    @Override
    public ProviderResult sendCode(CodeCommand command, WhatsAppProviderScopeEntity scope) {
        String custSpaceId = requireScope(scope);
        try (AsyncClient client = client(credentials(scope))) {
            GetChatappVerifyCodeResponse response = client.getChatappVerifyCode(
                    GetChatappVerifyCodeRequest.builder().custSpaceId(custSpaceId)
                            .phoneNumber(require(command.phoneNumber()))
                            .locale(require(command.locale())).method(require(command.method())).build())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            GetChatappVerifyCodeResponseBody body = response == null ? null : response.getBody();
            requireSuccess(body == null ? null : body.getSuccess(), body == null ? null : body.getCode());
            return new ProviderResult(custSpaceId, scope.getWabaId(), normalize(command.phoneNumber()),
                    "PENDING", "CODE_SENT", null);
        } catch (WhatsAppAuthorizationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    @Override
    public ProviderResult verify(VerifyCommand command, WhatsAppProviderScopeEntity scope) {
        String custSpaceId = requireScope(scope);
        try (AsyncClient client = client(credentials(scope))) {
            ChatappVerifyAndRegisterResponse response = client.chatappVerifyAndRegister(
                    ChatappVerifyAndRegisterRequest.builder().custSpaceId(custSpaceId)
                            .phoneNumber(require(command.phoneNumber()))
                            .verifyCode(require(command.verificationCode())).build())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ChatappVerifyAndRegisterResponseBody body = response == null ? null : response.getBody();
            requireSuccess(body == null ? null : body.getSuccess(), body == null ? null : body.getCode());
            PhoneNumbers matched = queryPhone(client, custSpaceId, normalize(command.phoneNumber()));
            return new ProviderResult(custSpaceId, scope.getWabaId(), normalize(matched.getPhoneNumber()),
                    phoneStatus(matched.getStatus()), verificationStatus(matched.getCodeVerificationStatus()),
                    matched.getVerifiedName());
        } catch (WhatsAppAuthorizationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private PhoneNumbers queryPhone(AsyncClient client, String custSpaceId, String phoneNumber) throws Exception {
        QueryChatappPhoneNumbersResponse response = client.queryChatappPhoneNumbers(
                QueryChatappPhoneNumbersRequest.builder().custSpaceId(custSpaceId).build())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        QueryChatappPhoneNumbersResponseBody body = body(response);
        requireSuccess(body == null ? null : body.getSuccess(), body == null ? null : body.getCode());
        return body == null || body.getPhoneNumbers() == null ? null : body.getPhoneNumbers().stream()
                .filter(number -> phoneNumber.equals(normalize(number.getPhoneNumber())))
                .findFirst().orElseThrow(AliyunWhatsAppOnboardingGateway::providerFailure);
    }

    private AsyncClient client(ProviderCredentials credentials) {
        return clientFactory.apply(credentials);
    }

    private static AsyncClient createClient(ProviderCredentials credentials) {
        return AsyncClient.builder().region(credentials.region())
                .credentialsProvider(StaticCredentialProvider.create(Credential.builder()
                        .accessKeyId(credentials.accessKeyId())
                        .accessKeySecret(credentials.accessKeySecret()).build()))
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(credentials.endpoint()))
                .build();
    }

    private ProviderCredentials credentials(WhatsAppProviderScopeEntity scope) {
        if (scope != null && !blank(scope.getEncryptedConfig())
                && !"{}".equals(scope.getEncryptedConfig().trim())) {
            try {
                Map<String, String> values = credentialCipher.decrypt(scope.getEncryptedConfig());
                return new ProviderCredentials(requiredConfig(values.get("accessKeyId")),
                        requiredConfig(values.get("accessKeySecret")),
                        value(values.get("region"), value(config.camsRegion(), "ap-southeast-1")),
                        value(values.get("endpoint"), value(config.camsEndpoint(),
                                "cams.ap-southeast-1.aliyuncs.com")));
            } catch (CredentialCipher.CredentialDecryptionException exception) {
                throw credentialsUnavailable();
            }
        }
        return globalProviderCredentials();
    }

    private ProviderCredentials globalProviderCredentials() {
        Map<String, String> values = globalCredentials();
        return new ProviderCredentials(values.get("accessKeyId"), values.get("accessKeySecret"),
                value(config.camsRegion(), "ap-southeast-1"),
                value(config.camsEndpoint(), "cams.ap-southeast-1.aliyuncs.com"));
    }

    private Map<String, String> globalCredentials() {
        if (blank(config.aliyunAccessKeyId()) || blank(config.aliyunAccessKeySecret())) {
            throw new WhatsAppAuthorizationException("WHATSAPP_PROVIDER_CREDENTIALS_MISSING",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("accessKeyId", config.aliyunAccessKeyId());
        values.put("accessKeySecret", config.aliyunAccessKeySecret());
        values.put("region", value(config.camsRegion(), "ap-southeast-1"));
        values.put("endpoint", value(config.camsEndpoint(), "cams.ap-southeast-1.aliyuncs.com"));
        return values;
    }

    private static String requiredConfig(String value) {
        if (blank(value)) {
            throw new WhatsAppAuthorizationException("WHATSAPP_PROVIDER_CREDENTIALS_MISSING",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }
        return value.trim();
    }

    private static WhatsAppAuthorizationException credentialsUnavailable() {
        return new WhatsAppAuthorizationException("WHATSAPP_PROVIDER_CREDENTIALS_UNREADABLE",
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static String requireScope(WhatsAppProviderScopeEntity scope) {
        if (scope == null || blank(scope.getExternalScopeId())) throw providerFailure();
        return scope.getExternalScopeId();
    }

    private static String require(String value) {
        if (blank(value)) throw providerFailure();
        return value.trim();
    }

    private static WhatsAppAuthorizationException unavailable() {
        return new WhatsAppAuthorizationException("WHATSAPP_PROVIDER_UNAVAILABLE",
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static void requireSuccess(Boolean success, String code) {
        if (!Boolean.TRUE.equals(success) && !"OK".equalsIgnoreCase(value(code))) {
            throw providerFailure();
        }
    }

    private static WhatsAppAuthorizationException providerFailure() {
        return new WhatsAppAuthorizationException("WHATSAPP_PROVIDER_PHONE_NOT_FOUND",
                org.springframework.http.HttpStatus.CONFLICT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static String phoneStatus(String status) {
        return "ACTIVE".equalsIgnoreCase(status) || "CONNECTED".equalsIgnoreCase(status)
                ? "ACTIVE" : value(status).toUpperCase();
    }

    private static String verificationStatus(String status) {
        return "VERIFIED".equalsIgnoreCase(status) ? "VERIFIED" : value(status).toUpperCase();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String requireValue(String value) {
        if (blank(value)) throw providerFailure();
        return value;
    }
    private static String value(String value) { return value == null ? "" : value.trim(); }
    private static String value(String value, String fallback) { return blank(value) ? fallback : value.trim(); }

    record ProviderCredentials(String accessKeyId, String accessKeySecret,
                                       String region, String endpoint) { }

    private static ChatappSyncPhoneNumberResponseBody body(ChatappSyncPhoneNumberResponse response) {
        return response == null ? null : response.getBody();
    }
    private static QueryChatappPhoneNumbersResponseBody body(QueryChatappPhoneNumbersResponse response) {
        return response == null ? null : response.getBody();
    }
}
