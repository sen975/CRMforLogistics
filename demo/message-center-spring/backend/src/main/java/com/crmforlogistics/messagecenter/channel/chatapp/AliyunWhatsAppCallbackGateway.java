package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.UpdateAccountWebhookRequest;
import com.aliyun.sdk.service.cams20200606.models.UpdateAccountWebhookResponse;
import com.aliyun.sdk.service.cams20200606.models.UpdatePhoneWebhookRequest;
import com.aliyun.sdk.service.cams20200606.models.UpdatePhoneWebhookResponse;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAuthorizationException;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppCallbackGateway;
import darabonba.core.client.ClientOverrideConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

@Component
public class AliyunWhatsAppCallbackGateway implements WhatsAppCallbackGateway {
    private static final int TIMEOUT_SECONDS = 30;
    private final Function<ChatAppAccountCredentials, AsyncClient> clientFactory;

    public AliyunWhatsAppCallbackGateway() {
        this(AliyunWhatsAppCallbackGateway::createClient);
    }

    AliyunWhatsAppCallbackGateway(Function<ChatAppAccountCredentials, AsyncClient> clientFactory) {
        this.clientFactory = Objects.requireNonNull(clientFactory);
    }

    @Override
    public ProviderApplyResult updatePhone(PhoneUpdate command, ChatAppAccountCredentials credentials) {
        Objects.requireNonNull(command, "command is required");
        Objects.requireNonNull(credentials, "credentials are required");
        UpdatePhoneWebhookRequest request = UpdatePhoneWebhookRequest.builder()
                .custSpaceId(required(command.custSpaceId(), "custSpaceId"))
                .phoneNumber(required(command.phoneNumber(), "phoneNumber"))
                .httpFlag(required(command.httpFlag(), "httpFlag"))
                .queueFlag(required(command.queueFlag(), "queueFlag"))
                .statusCallbackUrl(blankToNull(command.statusCallbackUrl()))
                .upCallbackUrl(blankToNull(command.upCallbackUrl()))
                .build();
        try (AsyncClient client = clientFactory.apply(credentials)) {
            UpdatePhoneWebhookResponse response = client.updatePhoneWebhook(request)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return parse(response == null ? null : response.getBody(), "UpdatePhoneWebhook");
        } catch (WhatsAppAuthorizationException exception) {
            throw exception;
        } catch (TimeoutException exception) {
            throw failure("WHATSAPP_CALLBACK_PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT);
        } catch (Exception exception) {
            throw failure("WHATSAPP_CALLBACK_PROVIDER_FAILED", HttpStatus.BAD_GATEWAY);
        }
    }

    @Override
    public ProviderApplyResult updateAccount(AccountUpdate command, ChatAppAccountCredentials credentials) {
        Objects.requireNonNull(command, "command is required");
        Objects.requireNonNull(credentials, "credentials are required");
        UpdateAccountWebhookRequest request = UpdateAccountWebhookRequest.builder()
                .custSpaceId(required(command.custSpaceId(), "custSpaceId"))
                .httpFlag(required(command.httpFlag(), "httpFlag"))
                .queueFlag(required(command.queueFlag(), "queueFlag"))
                .statusCallbackUrl(blankToNull(command.statusCallbackUrl()))
                .build();
        try (AsyncClient client = clientFactory.apply(credentials)) {
            UpdateAccountWebhookResponse response = client.updateAccountWebhook(request)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return parse(response == null ? null : response.getBody(), "UpdateAccountWebhook");
        } catch (WhatsAppAuthorizationException exception) {
            throw exception;
        } catch (TimeoutException exception) {
            throw failure("WHATSAPP_CALLBACK_PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT);
        } catch (Exception exception) {
            throw failure("WHATSAPP_CALLBACK_PROVIDER_FAILED", HttpStatus.BAD_GATEWAY);
        }
    }

    private static ProviderApplyResult parse(Object body, String action) {
        String code;
        String requestId;
        if (body instanceof com.aliyun.sdk.service.cams20200606.models.UpdatePhoneWebhookResponseBody phone) {
            code = phone.getCode();
            requestId = phone.getRequestId();
        } else if (body instanceof com.aliyun.sdk.service.cams20200606.models.UpdateAccountWebhookResponseBody account) {
            code = account.getCode();
            requestId = account.getRequestId();
        } else {
            throw failure("WHATSAPP_CALLBACK_PROVIDER_FAILED", HttpStatus.BAD_GATEWAY);
        }
        if (!isSuccess(code)) {
            throw failure("WHATSAPP_CALLBACK_PROVIDER_FAILED", HttpStatus.BAD_GATEWAY);
        }
        return new ProviderApplyResult(requestId == null ? "" : requestId);
    }

    private static boolean isSuccess(String code) {
        return "OK".equalsIgnoreCase(code) || "SUCCESS".equalsIgnoreCase(code);
    }

    private static AsyncClient createClient(ChatAppAccountCredentials credentials) {
        return AsyncClient.builder().region(required(credentials.region(), "region"))
                .credentialsProvider(StaticCredentialProvider.create(Credential.builder()
                        .accessKeyId(required(credentials.accessKeyId(), "accessKeyId"))
                        .accessKeySecret(required(credentials.accessKeySecret(), "accessKeySecret"))
                        .build()))
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(required(credentials.endpoint(), "endpoint")))
                .build();
    }

    private static WhatsAppAuthorizationException failure(String code, HttpStatus status) {
        return new WhatsAppAuthorizationException(code, status);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
