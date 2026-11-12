package com.crmforlogistics.messagecenter;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.DefaultCredentialProvider;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailRequest;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponseBody;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import darabonba.core.client.ClientOverrideConfiguration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

final class AliyunChatAppTemplateGateway implements ChatAppTemplateGateway {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Config config;
    private final AsyncClient client;

    AliyunChatAppTemplateGateway(Config config, AsyncClient client) {
        this.config = Objects.requireNonNull(config);
        this.client = Objects.requireNonNull(client);
    }

    static AliyunChatAppTemplateGateway open(Config config) {
        return new AliyunChatAppTemplateGateway(config, createClient(config));
    }

    @Override
    public TemplatePage listTemplates(int pageIndex, int pageSize, Duration timeout) throws Exception {
        var future = client.listChatappTemplate(request(pageIndex, pageSize));
        ListChatappTemplateResponse response;
        try {
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw exception;
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw exception;
        }
        ListChatappTemplateResponseBody body = requireSuccessfulBody(
                response == null ? null : response.getBody(), "ListChatappTemplate");
        List<TemplateSummary> summaries = body.getListTemplate() == null ? List.of()
                : body.getListTemplate().stream().map(AliyunChatAppTemplateGateway::toSummary).toList();
        return new TemplatePage(summaries, body.getTotal());
    }

    @Override
    public TemplateStore.TemplateRecord getTemplateDetail(TemplateSummary summary, Duration timeout) throws Exception {
        Objects.requireNonNull(summary);
        var future = client.getChatappTemplateDetail(detailRequest(summary));
        GetChatappTemplateDetailResponse response;
        try {
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw exception;
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw exception;
        }
        GetChatappTemplateDetailResponseBody.Data data = requireDetail(response, summary.templateCode());
        return toRecord(summary, data);
    }

    private ListChatappTemplateRequest request(int pageIndex, int pageSize) {
        ListChatappTemplateRequest.Builder builder = ListChatappTemplateRequest.builder()
                .custSpaceId(requiredConfig("CUST_SPACE_ID"))
                .page(ListChatappTemplateRequest.Page.builder()
                        .index(pageIndex)
                        .size(pageSize)
                        .build());
        putIfPresent("TEMPLATE_LANGUAGE", builder::language);
        putIfPresent("TEMPLATE_NAME", builder::name);
        putIfPresent("TEMPLATE_CODE", builder::code);
        putIfPresent("TEMPLATE_AUDIT_STATUS", builder::auditStatus);
        putIfPresent("TEMPLATE_CATEGORY", builder::category);
        putIfPresent("TEMPLATE_TYPE", builder::templateType);
        return builder.build();
    }

    private GetChatappTemplateDetailRequest detailRequest(TemplateSummary summary) {
        GetChatappTemplateDetailRequest.Builder builder = GetChatappTemplateDetailRequest.builder()
                .custSpaceId(requiredConfig("CUST_SPACE_ID"));
        putIfNotBlank(summary.templateCode(), builder::templateCode);
        putIfNotBlank(summary.templateName(), builder::templateName);
        putIfNotBlank(summary.language(), builder::language);
        putIfNotBlank(summary.templateType(), builder::templateType);
        return builder.build();
    }

    static TemplateSummary toSummary(ListChatappTemplateResponseBody.ListTemplate row) {
        return new TemplateSummary(row.getTemplateCode(), row.getTemplateName(), row.getLanguage(), row.getTemplateType());
    }

    static TemplateStore.TemplateRecord toRecord(
            TemplateSummary summary,
            GetChatappTemplateDetailResponseBody.Data data
    ) {
        TemplateStore.TemplateRecord record = new TemplateStore.TemplateRecord();
        record.templateCode = ContactPointUtil.firstNonBlank(data.getTemplateCode(), summary.templateCode());
        record.templateName = ContactPointUtil.firstNonBlank(data.getName(), summary.templateName());
        record.languageCode = ContactPointUtil.firstNonBlank(data.getLanguage(), summary.language());
        record.body = templateBody(data.getComponents());
        record.raw = GSON.toJson(data);
        record.updatedAt = Instant.now().toString();
        return record;
    }

    private static ListChatappTemplateResponseBody requireSuccessfulBody(
            ListChatappTemplateResponseBody body,
            String apiName
    ) {
        if (body == null) {
            throw new IllegalStateException(apiName + " returned empty body");
        }
        assertOk(apiName, body.getCode(), body.getMessage());
        if (Boolean.FALSE.equals(body.getSuccess())) {
            throw new IllegalStateException(apiName + " failed: " + body.getMessage());
        }
        return body;
    }

    private static GetChatappTemplateDetailResponseBody.Data requireDetail(
            GetChatappTemplateDetailResponse response,
            String templateCode
    ) {
        String apiName = "GetChatappTemplateDetail";
        GetChatappTemplateDetailResponseBody body = response == null ? null : response.getBody();
        if (body == null) {
            throw new IllegalStateException(apiName + " returned empty body for " + templateCode);
        }
        assertOk(apiName, body.getCode(), body.getMessage());
        if (body.getData() == null) {
            throw new IllegalStateException(apiName + " returned empty data for " + templateCode);
        }
        return body.getData();
    }

    private static String templateBody(List<GetChatappTemplateDetailResponseBody.Components> components) {
        if (components == null || components.isEmpty()) {
            return "";
        }
        List<String> fallbackText = new ArrayList<>();
        for (GetChatappTemplateDetailResponseBody.Components component : components) {
            String text = ContactPointUtil.firstNonBlank(component.getText(), component.getCaption(), "");
            if (text.isBlank()) {
                continue;
            }
            if ("BODY".equalsIgnoreCase(ContactPointUtil.firstNonBlank(component.getType(), ""))) {
                return text;
            }
            fallbackText.add(text);
        }
        return String.join("\n", fallbackText);
    }

    private static AsyncClient createClient(Config config) {
        return AsyncClient.builder()
                .region(config.value("CAMS_REGION", "ap-southeast-1"))
                .credentialsProvider(createCredentialsProvider(config))
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(config.value("CAMS_ENDPOINT", "cams.ap-southeast-1.aliyuncs.com")))
                .build();
    }

    private static ICredentialProvider createCredentialsProvider(Config config) {
        String accessKeyId = config.value("ALIYUN_ACCESS_KEY_ID", "");
        String accessKeySecret = config.value("ALIYUN_ACCESS_KEY_SECRET", "");
        if (!accessKeyId.isBlank() && !accessKeySecret.isBlank()) {
            return StaticCredentialProvider.create(Credential.builder()
                    .accessKeyId(accessKeyId)
                    .accessKeySecret(accessKeySecret)
                    .build());
        }
        return DefaultCredentialProvider.builder().build();
    }

    private String requiredConfig(String key) {
        String value = config.value(key, "");
        if (value.isBlank()) {
            throw new IllegalStateException("缺少配置: " + key);
        }
        return value;
    }

    private void putIfPresent(String key, Consumer<String> setter) {
        putIfNotBlank(config.value(key, ""), setter);
    }

    private static void putIfNotBlank(String value, Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    private static void assertOk(String apiName, String code, String message) {
        if (code != null && !code.isBlank() && !"OK".equalsIgnoreCase(code)) {
            throw new IllegalStateException(apiName + " failed: " + code + " " + message);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
