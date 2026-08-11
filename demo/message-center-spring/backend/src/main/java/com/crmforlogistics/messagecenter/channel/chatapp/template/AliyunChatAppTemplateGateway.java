package com.crmforlogistics.messagecenter.channel.chatapp.template;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.DefaultCredentialProvider;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.CreateChatappTemplateRequest;
import com.aliyun.sdk.service.cams20200606.models.CreateChatappTemplateResponse;
import com.aliyun.sdk.service.cams20200606.models.CreateChatappTemplateResponseBody;
import com.aliyun.sdk.service.cams20200606.models.DeleteChatappTemplateRequest;
import com.aliyun.sdk.service.cams20200606.models.DeleteChatappTemplateResponse;
import com.aliyun.sdk.service.cams20200606.models.DeleteChatappTemplateResponseBody;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailRequest;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailResponseBody;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationRequest;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ModifyChatappTemplatePropertiesRequest;
import com.aliyun.sdk.service.cams20200606.models.ModifyChatappTemplatePropertiesResponse;
import com.aliyun.sdk.service.cams20200606.models.ModifyChatappTemplatePropertiesResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ModifyChatappTemplateRequest;
import com.aliyun.sdk.service.cams20200606.models.ModifyChatappTemplateResponse;
import com.aliyun.sdk.service.cams20200606.models.ModifyChatappTemplateResponseBody;
import com.aliyun.sdk.gateway.pop.exception.PopClientException;
import com.aliyun.sdk.gateway.pop.exception.PopServerException;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppOssMediaUploader;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateGateway;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.CreateResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.DeleteResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ModifyResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.PropertyResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ProviderTemplatePage;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ProviderTemplateSummary;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ReviewStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateButton;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateSnapshot;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.UploadedMedia;
import darabonba.core.client.ClientOverrideConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.Duration;

@Service
public class AliyunChatAppTemplateGateway implements WhatsAppTemplateGateway {
    private static final String TEMPLATE_TYPE = "WHATSAPP";
    private static final Duration DEFAULT_AWAIT_TIMEOUT = Duration.ofSeconds(75);

    private final AppConfig config;
    private final AsyncClient client;
    private final ChatAppOssMediaUploader uploader;
    private final Duration awaitTimeout;

    public AliyunChatAppTemplateGateway(AppConfig config) {
        this(config, createClient(config), new ChatAppOssMediaUploader());
    }

    @Autowired
    public AliyunChatAppTemplateGateway(AppConfig config, ChatAppOssMediaUploader uploader) {
        this(config, createClient(config), uploader);
    }

    AliyunChatAppTemplateGateway(AppConfig config, AsyncClient client, ChatAppOssMediaUploader uploader) {
        this(config, client, uploader, DEFAULT_AWAIT_TIMEOUT);
    }

    AliyunChatAppTemplateGateway(AppConfig config, AsyncClient client, ChatAppOssMediaUploader uploader,
                                 Duration awaitTimeout) {
        this.config = Objects.requireNonNull(config);
        this.client = Objects.requireNonNull(client);
        this.uploader = Objects.requireNonNull(uploader);
        this.awaitTimeout = Objects.requireNonNull(awaitTimeout).compareTo(DEFAULT_AWAIT_TIMEOUT) > 0
                ? DEFAULT_AWAIT_TIMEOUT : awaitTimeout;
        if (awaitTimeout.isNegative() || awaitTimeout.isZero()) {
            throw new IllegalArgumentException("awaitTimeout must be positive");
        }
    }

    @Override
    public CreateResult create(UUID accountId, TemplateCommand command) {
        try {
            CreateChatappTemplateResponse response = await("create", client.createChatappTemplate(CreateChatappTemplateRequest.builder()
                    .custSpaceId(requiredSpace()).templateType(TEMPLATE_TYPE).name(command.name()).language(command.language())
                    .category(command.category()).components(createComponents(command.components()))
                    .example(flattenExamples(command.examples())).messageSendTtlSeconds(command.messageSendTtlSeconds()).build()));
            CreateChatappTemplateResponseBody body = response == null ? null : response.getBody();
            ensureCode("create", body, false);
            CreateChatappTemplateResponseBody.Data data = body.getData();
            if (data == null) throw rejected("create", body.getRequestId(), "CAMS returned empty data");
            return new CreateResult(data.getTemplateCode(), data.getTemplateName(), body.getRequestId());
        } catch (WhatsAppTemplateException e) {
            throw e;
        } catch (Exception e) {
            throw providerError("create", e, null);
        }
    }

    @Override
    public ModifyResult modify(UUID accountId, String templateCode, String language, TemplateCommand command) {
        try {
            ModifyChatappTemplateResponse response = await("modify", client.modifyChatappTemplate(ModifyChatappTemplateRequest.builder()
                    .custSpaceId(requiredSpace()).templateType(TEMPLATE_TYPE).templateCode(templateCode)
                    .templateName(command.name()).language(language).category(command.category())
                    .components(modifyComponents(command.components())).example(flattenExamples(command.examples()))
                    .messageSendTtlSeconds(command.messageSendTtlSeconds()).build()));
            ModifyChatappTemplateResponseBody body = response == null ? null : response.getBody();
            ensureCode("modify", body, false);
            ModifyChatappTemplateResponseBody.Data data = body.getData();
            if (data == null) throw rejected("modify", body.getRequestId(), "CAMS returned empty data");
            return new ModifyResult(data.getTemplateCode(), data.getTemplateName(), body.getRequestId());
        } catch (WhatsAppTemplateException e) {
            throw e;
        } catch (Exception e) {
            throw providerError("modify", e, null);
        }
    }

    @Override
    public PropertyResult setSendPermission(UUID accountId, String templateCode, String language, boolean allowSend) {
        try {
            ModifyChatappTemplatePropertiesResponse response = await("setSendPermission", client.modifyChatappTemplateProperties(
                    ModifyChatappTemplatePropertiesRequest.builder().custSpaceId(requiredSpace()).templateType(TEMPLATE_TYPE)
                            .templateCode(templateCode).language(language).allowSend(allowSend).build()));
            ModifyChatappTemplatePropertiesResponseBody body = response == null ? null : response.getBody();
            ensureCode("setSendPermission", body, true);
            return new PropertyResult(allowSend, body.getRequestId());
        } catch (WhatsAppTemplateException e) {
            throw e;
        } catch (Exception e) {
            throw providerError("setSendPermission", e, null);
        }
    }

    @Override
    public DeleteResult delete(UUID accountId, String templateCode, String language) {
        try {
            DeleteChatappTemplateResponse response = await("delete", client.deleteChatappTemplate(DeleteChatappTemplateRequest.builder()
                    .custSpaceId(requiredSpace()).templateType(TEMPLATE_TYPE).templateCode(templateCode).language(language).build()));
            DeleteChatappTemplateResponseBody body = response == null ? null : response.getBody();
            ensureCode("delete", body, true);
            return new DeleteResult(true, body.getRequestId());
        } catch (WhatsAppTemplateException e) {
            throw e;
        } catch (Exception e) {
            throw providerError("delete", e, null);
        }
    }

    @Override
    public ProviderTemplatePage list(UUID accountId, int page, int size) {
        try {
            ListChatappTemplateResponse response = await("list", client.listChatappTemplate(ListChatappTemplateRequest.builder()
                    .custSpaceId(requiredSpace()).templateType(TEMPLATE_TYPE)
                    .page(ListChatappTemplateRequest.Page.builder().index(page).size(size).build()).build()));
            ListChatappTemplateResponseBody body = response == null ? null : response.getBody();
            ensureCode("list", body, true);
            List<ListChatappTemplateResponseBody.ListTemplate> rows = body.getListTemplate() == null
                    ? List.of() : body.getListTemplate();
            List<ProviderTemplateSummary> items = rows.stream().map(row -> new ProviderTemplateSummary(
                    row.getTemplateCode(), row.getTemplateName(), row.getLanguage(), row.getCategory(),
                    row.getAuditStatus(), row.getReason(), row.getLastUpdateTime() == null
                    ? null : Instant.ofEpochMilli(row.getLastUpdateTime()))).toList();
            int total = body.getTotal() == null ? page * size : body.getTotal();
            return new ProviderTemplatePage(items, page, page * size < total);
        } catch (WhatsAppTemplateException e) {
            throw e;
        } catch (Exception e) {
            throw providerError("list", e, null);
        }
    }

    @Override
    public Optional<TemplateSnapshot> detail(UUID accountId, String templateCode, String language) {
        try {
            GetChatappTemplateDetailResponse response = await("detail", client.getChatappTemplateDetail(GetChatappTemplateDetailRequest.builder()
                    .custSpaceId(requiredSpace()).templateType(TEMPLATE_TYPE).templateCode(templateCode).language(language).build()));
            GetChatappTemplateDetailResponseBody body = response == null ? null : response.getBody();
            ensureCode("detail", body, false);
            GetChatappTemplateDetailResponseBody.Data data = body.getData();
            if (data == null) return Optional.empty();
            return Optional.of(new TemplateSnapshot(accountId, data.getTemplateCode(), data.getName(), data.getLanguage(),
                    data.getCategory(), reviewStatus(data.getAuditStatus()), data.getAuditStatus(), data.getReason(),
                    Boolean.TRUE.equals(data.getAllowSend()), detailComponents(data.getComponents()),
                    expandExamples(data.getExample()), data.getMessageSendTtlSeconds(), null, null));
        } catch (WhatsAppTemplateException e) {
            throw e;
        } catch (Exception e) {
            throw providerError("detail", e, null);
        }
    }

    @Override
    public UploadedMedia upload(UUID accountId, HeaderFormat format, byte[] bytes, String fileName, String contentType) {
        try {
            GetChatappUploadAuthorizationResponse response = await("upload", client.getChatappUploadAuthorization(
                    GetChatappUploadAuthorizationRequest.builder().custSpaceId(requiredSpace()).build()));
            GetChatappUploadAuthorizationResponseBody body = response == null ? null : response.getBody();
            ensureCode("upload", body, false);
            GetChatappUploadAuthorizationResponseBody.Data authorization = body.getData();
            if (authorization == null) throw rejected("upload", body.getRequestId(), "CAMS returned empty authorization");
            ChatAppOssMediaUploader.UploadedObject object = uploader.upload(authorization, bytes, fileName, contentType);
            return new UploadedMedia(object.objectKey(), object.url(), format, contentType, bytes.length, sha256(bytes));
        } catch (WhatsAppTemplateException e) {
            throw e;
        } catch (Exception e) {
            throw providerError("upload", e, null);
        }
    }

    private String requiredSpace() {
        if (config.custSpaceId() == null || config.custSpaceId().isBlank()) {
            throw new IllegalArgumentException("custSpaceId is required");
        }
        return config.custSpaceId().trim();
    }

    private static List<CreateChatappTemplateRequest.Components> createComponents(List<TemplateComponent> components) {
        return components.stream().map(component -> {
            CreateChatappTemplateRequest.Components.Builder builder = CreateChatappTemplateRequest.Components.builder()
                    .type(component.type().name()).text(component.text());
            if (component.headerFormat() != null) builder.format(component.headerFormat().name());
            if (component.mediaAssetId() != null) builder.url(component.mediaAssetId());
            if (!component.buttons().isEmpty()) builder.buttons(component.buttons().stream()
                    .map(AliyunChatAppTemplateGateway::createButton).toList());
            return builder.build();
        }).toList();
    }

    private static CreateChatappTemplateRequest.Buttons createButton(TemplateButton button) {
        return CreateChatappTemplateRequest.Buttons.builder().type(button.type().name()).text(button.text())
                .url(button.url()).phoneNumber(button.phoneNumber()).build();
    }

    private static List<ModifyChatappTemplateRequest.Components> modifyComponents(List<TemplateComponent> components) {
        return components.stream().map(component -> {
            ModifyChatappTemplateRequest.Components.Builder builder = ModifyChatappTemplateRequest.Components.builder()
                    .type(component.type().name()).text(component.text());
            if (component.headerFormat() != null) builder.format(component.headerFormat().name());
            if (component.mediaAssetId() != null) builder.url(component.mediaAssetId());
            if (!component.buttons().isEmpty()) builder.buttons(component.buttons().stream()
                    .map(AliyunChatAppTemplateGateway::modifyButton).toList());
            return builder.build();
        }).toList();
    }

    private static ModifyChatappTemplateRequest.Buttons modifyButton(TemplateButton button) {
        return ModifyChatappTemplateRequest.Buttons.builder().type(button.type().name()).text(button.text())
                .url(button.url()).phoneNumber(button.phoneNumber()).build();
    }

    private static Map<String, String> flattenExamples(Map<String, List<String>> examples) {
        Map<String, String> flattened = new LinkedHashMap<>();
        examples.forEach((key, values) -> flattened.put(key, values == null ? "" : String.join(",", values)));
        return flattened;
    }

    private static Map<String, List<String>> expandExamples(Map<String, String> examples) {
        if (examples == null) return Map.of();
        Map<String, List<String>> expanded = new LinkedHashMap<>();
        examples.forEach((key, value) -> expanded.put(key, value == null ? List.of() : List.of(value)));
        return expanded;
    }

    private static List<TemplateComponent> detailComponents(List<GetChatappTemplateDetailResponseBody.Components> components) {
        if (components == null) return List.of();
        return components.stream().map(component -> new TemplateComponent(componentType(component.getType()),
                headerFormat(component.getFormat()), component.getText(), component.getUrl(), detailButtons(component.getButtons()))).toList();
    }

    private static List<TemplateButton> detailButtons(List<GetChatappTemplateDetailResponseBody.Buttons> buttons) {
        if (buttons == null) return List.of();
        return buttons.stream().map(button -> new TemplateButton(
                buttonType(button.getType()), button.getText(), button.getUrl(), button.getPhoneNumber())).toList();
    }

    private static WhatsAppTemplateModels.ButtonType buttonType(String type) {
        try {
            return WhatsAppTemplateModels.ButtonType.valueOf(type == null ? "" : type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unsupported CAMS button type: " + type, e);
        }
    }

    private static ComponentType componentType(String type) {
        try {
            return ComponentType.valueOf(type == null ? "" : type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unsupported CAMS component type: " + type, e);
        }
    }

    private static HeaderFormat headerFormat(String format) {
        if (format == null || format.isBlank()) return null;
        try {
            return HeaderFormat.valueOf(format.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ReviewStatus reviewStatus(String status) {
        if (status == null) return ReviewStatus.UNKNOWN;
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "auditing" -> ReviewStatus.PENDING;
            case "pass" -> ReviewStatus.APPROVED;
            case "fail" -> ReviewStatus.REJECTED;
            case "unaudit" -> ReviewStatus.SUSPENDED;
            default -> ReviewStatus.UNKNOWN;
        };
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value));
        return hex.toString();
    }

    private void ensureCode(String operation, Object responseBody, boolean requireSuccess) {
        if (responseBody == null) {
            throw rejected(operation, null, "CAMS returned empty body");
        }
        String code;
        String requestId;
        Boolean success = null;
        if (responseBody instanceof CreateChatappTemplateResponseBody body) {
            code = body.getCode(); requestId = body.getRequestId();
        } else if (responseBody instanceof ModifyChatappTemplateResponseBody body) {
            code = body.getCode(); requestId = body.getRequestId();
        } else if (responseBody instanceof ModifyChatappTemplatePropertiesResponseBody body) {
            code = body.getCode(); requestId = body.getRequestId(); success = body.getSuccess();
        } else if (responseBody instanceof DeleteChatappTemplateResponseBody body) {
            code = body.getCode(); requestId = body.getRequestId(); success = body.getSuccess();
        } else if (responseBody instanceof ListChatappTemplateResponseBody body) {
            code = body.getCode(); requestId = body.getRequestId(); success = body.getSuccess();
        } else if (responseBody instanceof GetChatappTemplateDetailResponseBody body) {
            code = body.getCode(); requestId = body.getRequestId();
        } else if (responseBody instanceof GetChatappUploadAuthorizationResponseBody body) {
            code = body.getCode(); requestId = body.getRequestId();
        } else {
            throw rejected(operation, null, "Unsupported CAMS response body");
        }
        if (code == null || code.isBlank() || !SetOfSuccessCodes.CODES.contains(code.trim().toUpperCase(Locale.ROOT))) {
            throw rejected(operation, requestId, "CAMS rejected request: " + code);
        }
        if (requireSuccess && !Boolean.TRUE.equals(success)) {
            throw rejected(operation, requestId, "CAMS response success was not true");
        }
    }

    private static final class SetOfSuccessCodes {
        private static final java.util.Set<String> CODES = java.util.Set.of("OK", "200", "SUCCESS");
    }

    private static WhatsAppTemplateException rejected(String operation, String requestId, String message) {
        return new WhatsAppTemplateException("TEMPLATE_PROVIDER_REJECTED", org.springframework.http.HttpStatus.BAD_GATEWAY,
                operation + ": " + message, Map.of(), requestId, false);
    }

    private static WhatsAppTemplateException providerError(String operation, Throwable error, String requestId) {
        Throwable cause = error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
        String providerRequestId = requestId == null ? requestId(cause) : requestId;
        return new WhatsAppTemplateException("TEMPLATE_PROVIDER_ERROR", org.springframework.http.HttpStatus.BAD_GATEWAY,
                operation + " failed: " + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()),
                Map.of(), providerRequestId, true);
    }

    private static String requestId(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof PopClientException clientException && clientException.getRequestId() != null) {
                return clientException.getRequestId();
            }
            if (current instanceof PopServerException serverException && serverException.getRequestId() != null) {
                return serverException.getRequestId();
            }
            current = current.getCause();
        }
        return null;
    }

    private <T> T await(String operation, Future<T> future) {
        try {
            return future.get(awaitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WhatsAppTemplateException("TEMPLATE_PROVIDER_INTERRUPTED", org.springframework.http.HttpStatus.BAD_GATEWAY,
                    operation + " interrupted", Map.of(), null, true);
        } catch (TimeoutException e) {
            throw new WhatsAppTemplateException("TEMPLATE_PROVIDER_TIMEOUT", org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                    operation + " timed out", Map.of(), null, true);
        } catch (ExecutionException e) {
            throw providerError(operation, e, null);
        } catch (Exception e) {
            throw providerError(operation, e, null);
        }
    }

    private static AsyncClient createClient(AppConfig config) {
        return AsyncClient.builder().region(defaulted(config.camsRegion(), "ap-southeast-1"))
                .credentialsProvider(credentials(config))
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(defaulted(config.camsEndpoint(), "cams.ap-southeast-1.aliyuncs.com"))).build();
    }

    private static ICredentialProvider credentials(AppConfig config) {
        if (config.aliyunAccessKeyId() != null && !config.aliyunAccessKeyId().isBlank()
                && config.aliyunAccessKeySecret() != null && !config.aliyunAccessKeySecret().isBlank()) {
            return StaticCredentialProvider.create(Credential.builder().accessKeyId(config.aliyunAccessKeyId())
                    .accessKeySecret(config.aliyunAccessKeySecret()).build());
        }
        return DefaultCredentialProvider.builder().build();
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
