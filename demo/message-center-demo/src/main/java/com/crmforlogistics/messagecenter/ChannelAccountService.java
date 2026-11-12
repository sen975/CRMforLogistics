package com.crmforlogistics.messagecenter;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponseBody;
import darabonba.core.client.ClientOverrideConfiguration;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ChannelAccountService {
    private static final ValidationResult NOT_FOUND = new ValidationResult(
            false, "CHANNEL_ACCOUNT_NOT_FOUND", "Channel account not found");
    private static final ValidationResult DECRYPTION_FAILED = new ValidationResult(
            false, "CREDENTIAL_DECRYPTION_FAILED", "Unable to read channel credentials");
    private static final ValidationResult VALIDATION_FAILED = new ValidationResult(
            false, "CHANNEL_VALIDATION_FAILED", "Unable to validate channel credentials");
    private static final String CAMS_REGION = "ap-southeast-1";
    private static final String CAMS_ENDPOINT = "cams.ap-southeast-1.aliyuncs.com";
    private static final long CAMS_VALIDATION_TIMEOUT_SECONDS = 10L;

    private final ChannelAccountRepository repository;
    private final CredentialCipher cipher;
    private final ChatAppValidationTransport chatAppValidationTransport;

    public ChannelAccountService(ChannelAccountRepository repository, CredentialCipher cipher) {
        this(repository, cipher, new CamsChatAppValidationTransport());
    }

    ChannelAccountService(ChannelAccountRepository repository, CredentialCipher cipher,
                          ChatAppValidationTransport chatAppValidationTransport) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.chatAppValidationTransport = Objects.requireNonNull(
                chatAppValidationTransport, "chatAppValidationTransport");
    }

    public ValidationResult validateChatApp(UUID accountId) throws Exception {
        return validate(accountId, (account, secrets) -> {
            if (!"chatapp".equals(account.channelType())) {
                return new ValidationResult(false, "INVALID_CREDENTIALS", "");
            }
            String accessKeyId = secrets.get("accessKeyId");
            String accessKeySecret = secrets.get("accessKeySecret");
            if (accessKeyId == null || accessKeyId.isBlank()
                    || accessKeySecret == null || accessKeySecret.isBlank()
                    || account.accountIdentifier() == null || account.accountIdentifier().isBlank()) {
                return new ValidationResult(false, "INVALID_CREDENTIALS", "");
            }
            char[] secret = accessKeySecret.toCharArray();
            try (ChatAppValidationRequest request = new ChatAppValidationRequest(
                    account.accountIdentifier(), accessKeyId, secret,
                    CAMS_REGION, CAMS_ENDPOINT, 1, 1)) {
                ChatAppValidationResponse response = chatAppValidationTransport.validate(request);
                return new ValidationResult(response.accepted(),
                        response.accepted() ? "VALID" : "INVALID_CREDENTIALS", "");
            } finally {
                Arrays.fill(secret, '\0');
            }
        });
    }

    public ValidationResult validate(UUID accountId, CredentialValidator validator) throws Exception {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(validator, "validator");
        Optional<ChannelAccount> account = repository.find(accountId);
        if (account.isEmpty()) {
            return NOT_FOUND;
        }

        Map<String, String> decrypted;
        try {
            decrypted = new LinkedHashMap<>(cipher.decrypt(account.get().encryptedConfig()));
        } catch (CredentialCipher.CredentialDecryptionException exception) {
            return DECRYPTION_FAILED;
        }

        try {
            ChannelAccount safeAccount = withoutEncryptedConfig(account.get());
            ValidationResult result = validator.validate(
                    safeAccount, Collections.unmodifiableMap(decrypted));
            if (result == null) {
                return VALIDATION_FAILED;
            }
            return result.valid()
                    ? new ValidationResult(true, "VALID", "Channel credentials are valid")
                    : new ValidationResult(false, "INVALID_CREDENTIALS", "Channel credentials are invalid");
        } catch (Exception exception) {
            return VALIDATION_FAILED;
        } finally {
            decrypted.replaceAll((key, value) -> "");
            decrypted.clear();
        }
    }

    private static ChannelAccount withoutEncryptedConfig(ChannelAccount account) {
        return new ChannelAccount(
                account.id(),
                account.channelType(),
                account.name(),
                account.accountIdentifier(),
                account.authStatus(),
                account.syncStatus(),
                "");
    }

    private static final class CamsChatAppValidationTransport implements ChatAppValidationTransport {
        @Override
        public ChatAppValidationResponse validate(ChatAppValidationRequest request) throws Exception {
            String secret = new String(request.accessKeySecret());
            StaticCredentialProvider provider = StaticCredentialProvider.create(Credential.builder()
                    .accessKeyId(request.accessKeyId())
                    .accessKeySecret(secret)
                    .build());
            secret = null;
            try (AsyncClient client = AsyncClient.builder()
                    .region(request.region())
                    .credentialsProvider(provider)
                    .overrideConfiguration(ClientOverrideConfiguration.create()
                            .setEndpointOverride(request.endpoint()))
                    .build()) {
                ListChatappTemplateRequest sdkRequest = ListChatappTemplateRequest.builder()
                        .custSpaceId(request.custSpaceId())
                        .page(ListChatappTemplateRequest.Page.builder()
                                .index(request.pageIndex())
                                .size(request.pageSize())
                                .build())
                        .build();
                ListChatappTemplateResponse response = client.listChatappTemplate(sdkRequest)
                        .get(CAMS_VALIDATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ListChatappTemplateResponseBody body = response == null ? null : response.getBody();
                if (body == null || Boolean.FALSE.equals(body.getSuccess())) {
                    return new ChatAppValidationResponse(false);
                }
                String code = body.getCode();
                boolean accepted = Boolean.TRUE.equals(body.getSuccess())
                        || (code != null && "OK".equalsIgnoreCase(code));
                return new ChatAppValidationResponse(accepted);
            }
        }
    }
}

interface ChatAppValidationTransport {
    ChatAppValidationResponse validate(ChatAppValidationRequest request) throws Exception;
}

record ChatAppValidationRequest(String custSpaceId, String accessKeyId, char[] accessKeySecret,
                                String region, String endpoint, int pageIndex, int pageSize)
        implements AutoCloseable {
    ChatAppValidationRequest {
        Objects.requireNonNull(custSpaceId, "custSpaceId");
        Objects.requireNonNull(accessKeyId, "accessKeyId");
        Objects.requireNonNull(accessKeySecret, "accessKeySecret");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(endpoint, "endpoint");
    }

    @Override
    public void close() {
        Arrays.fill(accessKeySecret, '\0');
    }
}

record ChatAppValidationResponse(boolean accepted) {}
