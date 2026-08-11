package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponseBody;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMessageResponse;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMessageResponseBody;
import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatAppSendServiceTest {

    @Mock
    AppConfig appConfig;

    @Test
    void shouldConstructWithAppConfig() {
        ChatAppSendService service = new ChatAppSendService(appConfig);
        assertNotNull(service);
    }

    @Test
    void shouldRejectNullAppConfig() {
        assertThrows(NullPointerException.class, () -> new ChatAppSendService(null));
    }

    @Test
    void shouldRetainDefaultConstructorForExistingMessageSenders() {
        assertNotNull(new ChatAppSendService(appConfig));
    }

    @Test
    void mediaSendRejectsMissingCamsMessageId() throws Exception {
        when(appConfig.custSpaceId()).thenReturn("space");
        when(appConfig.chatappFrom()).thenReturn("from");
        AsyncClient client = mock(AsyncClient.class);
        when(client.getChatappUploadAuthorization(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                GetChatappUploadAuthorizationResponse.create().toBuilder().body(GetChatappUploadAuthorizationResponseBody.builder()
                        .code("OK").data(GetChatappUploadAuthorizationResponseBody.Data.builder()
                                .bucketName("bucket").dir("dir").endPoint("oss.example.com")
                                .accessKeyId("id").accessKeySecret("secret").build()).build()).build()));
        when(client.sendChatappMessage(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                SendChatappMessageResponse.create().toBuilder().body(SendChatappMessageResponseBody.builder().build()).build()));
        ChatAppOssMediaUploader uploader = mock(ChatAppOssMediaUploader.class);
        when(uploader.upload(any(), any(), any(), any())).thenReturn(
                new ChatAppOssMediaUploader.UploadedObject("dir/a.png", "https://bucket.oss.example.com/dir/a.png"));
        ChatAppSendService service = new ChatAppSendService(appConfig, uploader, () -> client);

        assertThrows(IllegalStateException.class,
                () -> service.sendMedia("to", "image", new byte[]{1}, "a.png", "image/png", null, null));
    }
}
