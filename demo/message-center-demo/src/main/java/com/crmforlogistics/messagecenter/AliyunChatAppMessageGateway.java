package com.crmforlogistics.messagecenter;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.DefaultCredentialProvider;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import darabonba.core.client.ClientOverrideConfiguration;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class AliyunChatAppMessageGateway implements ChatAppMessageGateway {
    private static final Duration MAX_LIST_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    @FunctionalInterface
    interface ListCall {
        Future<ListChatappMessageResponse> execute(ListChatappMessageRequest request);
    }

    private final ListCall listCall;
    private final Runnable closeAction;

    AliyunChatAppMessageGateway(ListCall listCall, Runnable closeAction) {
        this.listCall = Objects.requireNonNull(listCall);
        this.closeAction = Objects.requireNonNull(closeAction);
    }

    static AliyunChatAppMessageGateway open(Config config) {
        AsyncClient client = createClient(config);
        return new AliyunChatAppMessageGateway(client::listChatappMessage, client::close);
    }

    @Override
    public MessagePage listMessages(MessageRequest request, Duration timeout) throws Exception {
        Objects.requireNonNull(request);
        Objects.requireNonNull(timeout);
        Future<ListChatappMessageResponse> future = listCall.execute(toAliyunRequest(request));
        final ListChatappMessageResponse response;
        Duration boundedTimeout = timeout.compareTo(MAX_LIST_REQUEST_TIMEOUT) > 0
                ? MAX_LIST_REQUEST_TIMEOUT : timeout;
        try {
            response = future.get(boundedTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw exception;
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw exception;
        }
        ListChatappMessageResponseBody body = response == null ? null : response.getBody();
        if (body == null) {
            throw new IllegalStateException("ListChatappMessage returned empty body");
        }
        assertOk("ListChatappMessage", body.getCode(), body.getMessage());
        if (Boolean.FALSE.equals(body.getSuccess())) {
            throw new IllegalStateException("ListChatappMessage failed: " + body.getMessage());
        }
        return new MessagePage(body.getData() == null ? List.of() : body.getData());
    }

    private static ListChatappMessageRequest toAliyunRequest(MessageRequest request) {
        ListChatappMessageRequest.Builder builder = ListChatappMessageRequest.builder()
                .custSpaceId(request.custSpaceId())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .page(ListChatappMessageRequest.Page.builder()
                        .index((long) request.pageIndex())
                        .size((long) request.pageSize())
                        .build());
        putIfNotBlank(request.channelType(), builder::channelType);
        putIfNotBlank(request.businessNumber(), builder::businessNumber);
        putIfNotBlank(request.userNumber(), builder::userNumber);
        putIfNotBlank(request.messageStatus(), builder::messageStatus);
        putIfNotBlank(request.clientAcceptStatus(), builder::clientAcceptStatus);
        return builder.build();
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

    private static void putIfNotBlank(String value, java.util.function.Consumer<String> setter) {
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
        closeAction.run();
    }
}
