package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponseBody;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMessageResponse;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMessageResponseBody;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAppSendServiceTest {

    @Test
    void shouldConstructWithMediaUploader() {
        ChatAppSendService service = new ChatAppSendService(mock(ChatAppOssMediaUploader.class));
        assertNotNull(service);
    }

    @Test
    void shouldRejectNullMediaUploader() {
        assertThrows(NullPointerException.class, () -> new ChatAppSendService(null));
    }

    @Test
    void exposesOnlyCredentialScopedProviderSendMethods() {
        assertThat(Arrays.stream(ChatAppSendService.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().matches("send(Text|Template|Media)")))
                .allSatisfy(method -> assertThat(method.getParameterTypes()[0])
                        .isEqualTo(ChatAppAccountCredentials.class));
    }

    @Test
    void mediaSendRejectsMissingCamsMessageId() throws Exception {
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
        ChatAppSendService service = new ChatAppSendService(uploader, () -> client);
        ChatAppAccountCredentials credentials = new ChatAppAccountCredentials(
                "access-key", "access-secret", "space", "from",
                "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com");

        assertThrows(IllegalStateException.class,
                () -> service.sendMedia(credentials, "from", "to", "image", new byte[]{1},
                        "a.png", "image/png", null, null));
    }
}
