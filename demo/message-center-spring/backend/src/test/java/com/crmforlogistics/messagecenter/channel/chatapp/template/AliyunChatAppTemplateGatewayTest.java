package com.crmforlogistics.messagecenter.channel.chatapp.template;

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
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppOssMediaUploader;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsException;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsResolver;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.CreateResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateButton;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ButtonType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import com.aliyun.sdk.gateway.pop.exception.PopClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

import static com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType.BODY;
import static com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType.FOOTER;
import static com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType.HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class AliyunChatAppTemplateGatewayTest {

    @Mock
    private AsyncClient client;
    @Mock
    private ChatAppOssMediaUploader uploader;

    private AliyunChatAppTemplateGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new AliyunChatAppTemplateGateway(client, uploader, "cams-space");
    }

    @Test
    void createMapsOnlyOfficialWhatsappTemplateRequest() {
        when(client.createChatappTemplate(any())).thenReturn(CompletableFuture.completedFuture(
                CreateChatappTemplateResponse.create().toBuilder().body(CreateChatappTemplateResponseBody.builder()
                        .code("OK").requestId("req-create")
                        .data(CreateChatappTemplateResponseBody.Data.builder()
                                .templateCode("tpl-1").templateName("delivery_notice").build())
                        .build()).build()));

        CreateResult result = gateway.create(UUID.randomUUID(), command());

        ArgumentCaptor<CreateChatappTemplateRequest> request = ArgumentCaptor.forClass(CreateChatappTemplateRequest.class);
        verify(client).createChatappTemplate(request.capture());
        assertThat(request.getValue().getTemplateType()).isEqualTo("WHATSAPP");
        assertThat(request.getValue().getCategory()).isEqualTo("UTILITY");
        assertThat(request.getValue().getCustSpaceId()).isEqualTo("cams-space");
        assertThat(request.getValue().getComponents()).hasSize(4);
        assertThat(request.getValue().getComponents().get(1).getText()).isEqualTo("Hello $(customer)");
        assertThat(request.getValue().getComponents().get(3).getButtons())
                .extracting(button -> button.getType()).containsExactly("QUICK_REPLY", "URL", "PHONE_NUMBER");
        assertThat(request.getValue().getComponents().get(3).getButtons().get(1).getUrl()).isEqualTo("https://example.test/{{1}}");
        assertThat(request.getValue().getExample()).containsEntry("customer", "Ada,Bea");
        assertThat(result.templateCode()).isEqualTo("tpl-1");
        assertThat(result.providerRequestId()).isEqualTo("req-create");
    }

    @Test
    void exposesAccountCredentialFailureInsteadOfGenericProviderError() {
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        ChatAppAccountCredentialsResolver credentialsResolver = mock(ChatAppAccountCredentialsResolver.class);
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        account.setAccountIdentifier("60122222222");
        when(accountMapper.selectById(accountId)).thenReturn(account);
        when(credentialsResolver.resolve(account)).thenThrow(
                new ChatAppAccountCredentialsException("CHATAPP_ACCOUNT_CREDENTIALS_MISSING"));
        AliyunChatAppTemplateGateway accountGateway = new AliyunChatAppTemplateGateway(
                uploader, accountMapper, credentialsResolver);

        assertThatThrownBy(() -> accountGateway.create(accountId, command()))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class, error -> {
                    assertThat(error.code()).isEqualTo("CHATAPP_ACCOUNT_CREDENTIALS_MISSING");
                    assertThat(error.retryable()).isFalse();
                });
    }

    @Test
    void modifyMapsOfficialRequest() {
        when(client.modifyChatappTemplate(any())).thenReturn(CompletableFuture.completedFuture(
                ModifyChatappTemplateResponse.create().toBuilder().body(ModifyChatappTemplateResponseBody.builder()
                        .code("OK").requestId("req-modify")
                        .data(ModifyChatappTemplateResponseBody.Data.builder()
                                .templateCode("tpl-1").templateName("delivery_notice").build())
                        .build()).build()));

        gateway.modify(UUID.randomUUID(), "tpl-1", "en_US", command());

        ArgumentCaptor<ModifyChatappTemplateRequest> request = ArgumentCaptor.forClass(ModifyChatappTemplateRequest.class);
        verify(client).modifyChatappTemplate(request.capture());
        assertThat(request.getValue().getTemplateCode()).isEqualTo("tpl-1");
        assertThat(request.getValue().getLanguage()).isEqualTo("en_US");
        assertThat(request.getValue().getTemplateType()).isEqualTo("WHATSAPP");
    }

    @Test
    void propertiesUsesOfficialPropertiesOperationAndChecksSuccessBody() {
        when(client.modifyChatappTemplateProperties(any())).thenReturn(CompletableFuture.completedFuture(
                ModifyChatappTemplatePropertiesResponse.create().toBuilder().body(ModifyChatappTemplatePropertiesResponseBody.builder()
                        .code("OK").success(true).requestId("req-props").build()).build()));

        var result = gateway.setSendPermission(UUID.randomUUID(), "tpl-1", "en_US", false);

        ArgumentCaptor<ModifyChatappTemplatePropertiesRequest> request =
                ArgumentCaptor.forClass(ModifyChatappTemplatePropertiesRequest.class);
        verify(client).modifyChatappTemplateProperties(request.capture());
        assertThat(request.getValue().getAllowSend()).isFalse();
        assertThat(request.getValue().getTemplateType()).isEqualTo("WHATSAPP");
        assertThat(result.providerRequestId()).isEqualTo("req-props");
    }

    @Test
    void deleteUsesOfficialDeleteOperation() {
        when(client.deleteChatappTemplate(any())).thenReturn(CompletableFuture.completedFuture(
                DeleteChatappTemplateResponse.create().toBuilder().body(DeleteChatappTemplateResponseBody.builder()
                        .code("OK").success(true).requestId("req-delete").build()).build()));

        var result = gateway.delete(UUID.randomUUID(), "tpl-1", "en_US");

        ArgumentCaptor<DeleteChatappTemplateRequest> request = ArgumentCaptor.forClass(DeleteChatappTemplateRequest.class);
        verify(client).deleteChatappTemplate(request.capture());
        assertThat(request.getValue().getTemplateCode()).isEqualTo("tpl-1");
        assertThat(result.success()).isTrue();
    }

    @Test
    void listMapsProviderSummariesAndPage() {
        var row = ListChatappTemplateResponseBody.ListTemplate.builder().templateCode("tpl-1")
                .templateName("delivery_notice").language("en_US").category("UTILITY")
                .auditStatus("pass").lastUpdateTime(1_700_000_000_000L).build();
        when(client.listChatappTemplate(any())).thenReturn(CompletableFuture.completedFuture(
                ListChatappTemplateResponse.create().toBuilder().body(ListChatappTemplateResponseBody.builder()
                        .code("OK").success(true).total(3).listTemplate(List.of(row)).build()).build()));

        var page = gateway.list(UUID.randomUUID(), 2, 1);

        ArgumentCaptor<ListChatappTemplateRequest> request = ArgumentCaptor.forClass(ListChatappTemplateRequest.class);
        verify(client).listChatappTemplate(request.capture());
        assertThat(request.getValue().getPage().getIndex()).isEqualTo(2);
        assertThat(request.getValue().getPage().getSize()).isEqualTo(1);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.items()).extracting(item -> item.templateCode()).containsExactly("tpl-1");
    }

    @Test
    void detailMapsOfficialDetailResponse() {
        var data = GetChatappTemplateDetailResponseBody.Data.builder().templateCode("tpl-1")
                .name("delivery_notice").language("en_US").category("UTILITY").auditStatus("pass")
                .allowSend(true).components(List.of(
                        GetChatappTemplateDetailResponseBody.Components.builder()
                                .type("BODY").text("Hello $(customer)").build(),
                        GetChatappTemplateDetailResponseBody.Components.builder().type("BUTTONS")
                                .buttons(List.of(
                                        GetChatappTemplateDetailResponseBody.Buttons.builder()
                                                .type("QUICK_REPLY").text("Yes").build(),
                                        GetChatappTemplateDetailResponseBody.Buttons.builder()
                                                .type("URL").text("Track").url("https://example.test").build(),
                                        GetChatappTemplateDetailResponseBody.Buttons.builder()
                                                .type("PHONE_NUMBER").text("Call").phoneNumber("+123456789").build()))
                                .build())).build();
        when(client.getChatappTemplateDetail(any())).thenReturn(CompletableFuture.completedFuture(
                GetChatappTemplateDetailResponse.create().toBuilder().body(GetChatappTemplateDetailResponseBody.builder()
                        .code("OK").data(data).build()).build()));

        var detail = gateway.detail(UUID.randomUUID(), "tpl-1", "en_US");

        ArgumentCaptor<GetChatappTemplateDetailRequest> request = ArgumentCaptor.forClass(GetChatappTemplateDetailRequest.class);
        verify(client).getChatappTemplateDetail(request.capture());
        assertThat(request.getValue().getTemplateType()).isEqualTo("WHATSAPP");
        assertThat(detail).isPresent();
        assertThat(detail.orElseThrow().allowSend()).isTrue();
        assertThat(detail.orElseThrow().components().get(1).buttons())
                .extracting(button -> button.type())
                .containsExactly(ButtonType.QUICK_REPLY, ButtonType.URL, ButtonType.PHONE_NUMBER);
        assertThat(detail.orElseThrow().components().get(1).buttons().get(1).url())
                .isEqualTo("https://example.test");
    }

    @Test
    void uploadRequestsCamsAuthorizationBeforeDelegatingOssPut() throws Exception {
        var authorization = GetChatappUploadAuthorizationResponseBody.Data.builder().bucketName("cams-media")
                .dir("templates").endPoint("oss.example.com").accessKeyId("id").accessKeySecret("secret").build();
        when(client.getChatappUploadAuthorization(any())).thenReturn(CompletableFuture.completedFuture(
                GetChatappUploadAuthorizationResponse.create().toBuilder().body(GetChatappUploadAuthorizationResponseBody.builder()
                        .code("OK").data(authorization).build()).build()));
        var uploaded = new ChatAppOssMediaUploader.UploadedObject("templates/a.png", "https://cams-media.oss.example.com/templates/a.png");
        when(uploader.upload(authorization, new byte[]{1, 2}, "a.png", "image/png")).thenReturn(uploaded);

        var result = gateway.upload(UUID.randomUUID(), HeaderFormat.IMAGE, new byte[]{1, 2}, "a.png", "image/png");

        ArgumentCaptor<GetChatappUploadAuthorizationRequest> request =
                ArgumentCaptor.forClass(GetChatappUploadAuthorizationRequest.class);
        verify(client).getChatappUploadAuthorization(request.capture());
        assertThat(request.getValue().getCustSpaceId()).isEqualTo("cams-space");
        assertThat(result.objectKey()).isEqualTo("templates/a.png");
        assertThat(result.url()).isEqualTo(uploaded.url());
    }

    @Test
    void rejectsBlankCodeAndMissingSuccessWithRequestId() {
        when(client.createChatappTemplate(any())).thenReturn(CompletableFuture.completedFuture(
                CreateChatappTemplateResponse.create().toBuilder().body(CreateChatappTemplateResponseBody.builder()
                        .code(" ").requestId("req-blank").build()).build()));
        assertThatThrownBy(() -> gateway.create(UUID.randomUUID(), command()))
                .isInstanceOf(WhatsAppTemplateException.class).satisfies(error -> {
                    var e = (WhatsAppTemplateException) error;
                    assertThat(e.code()).isEqualTo("TEMPLATE_PROVIDER_REJECTED");
                    assertThat(e.providerRequestId()).isEqualTo("req-blank");
                });

        when(client.deleteChatappTemplate(any())).thenReturn(CompletableFuture.completedFuture(
                DeleteChatappTemplateResponse.create().toBuilder().body(DeleteChatappTemplateResponseBody.builder()
                        .code("OK").requestId("req-no-success").build()).build()));
        assertThatThrownBy(() -> gateway.delete(UUID.randomUUID(), "tpl-1", "en_US"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("providerRequestId").isEqualTo("req-no-success");

        when(client.modifyChatappTemplateProperties(any())).thenReturn(CompletableFuture.completedFuture(
                ModifyChatappTemplatePropertiesResponse.create().toBuilder().body(
                        ModifyChatappTemplatePropertiesResponseBody.builder().code("OK").success(false)
                                .requestId("req-false-success").build()).build()));
        assertThatThrownBy(() -> gateway.setSendPermission(UUID.randomUUID(), "tpl-1", "en_US", true))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("providerRequestId").isEqualTo("req-false-success");

        when(client.listChatappTemplate(any())).thenReturn(CompletableFuture.completedFuture(
                ListChatappTemplateResponse.create().toBuilder().body(ListChatappTemplateResponseBody.builder()
                        .code("OK").requestId("req-list-no-success").build()).build()));
        assertThatThrownBy(() -> gateway.list(UUID.randomUUID(), 1, 10))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("providerRequestId").isEqualTo("req-list-no-success");
    }

    @Test
    void timesOutWithStableRetryableError() {
        when(client.createChatappTemplate(any())).thenReturn(new CompletableFuture<>());
        AliyunChatAppTemplateGateway shortGateway = new AliyunChatAppTemplateGateway(
                client, uploader, Duration.ofMillis(10), "cams-space");
        assertThatThrownBy(() -> shortGateway.create(UUID.randomUUID(), command()))
                .isInstanceOf(WhatsAppTemplateException.class).satisfies(error -> {
                    var e = (WhatsAppTemplateException) error;
                    assertThat(e.code()).isEqualTo("TEMPLATE_PROVIDER_TIMEOUT");
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void preservesRequestIdFromPopProviderException() {
        PopClientException provider = new PopClientException("provider failed");
        provider.setRequestId("pop-req-1");
        when(client.createChatappTemplate(any())).thenReturn(CompletableFuture.failedFuture(provider));
        assertThatThrownBy(() -> gateway.create(UUID.randomUUID(), command()))
                .isInstanceOf(WhatsAppTemplateException.class).satisfies(error -> {
                    var e = (WhatsAppTemplateException) error;
                    assertThat(e.code()).isEqualTo("TEMPLATE_PROVIDER_ERROR");
                    assertThat(e.providerRequestId()).isEqualTo("pop-req-1");
                });
    }

    @Test
    void restoresInterruptStatusAndReturnsStableError() {
        CompletableFuture<CreateChatappTemplateResponse> interrupted = new CompletableFuture<>() {
            @Override
            public CreateChatappTemplateResponse get(long timeout, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("test interrupt");
            }
        };
        when(client.createChatappTemplate(any())).thenReturn(interrupted);

        try {
            assertThatThrownBy(() -> gateway.create(UUID.randomUUID(), command()))
                    .isInstanceOf(WhatsAppTemplateException.class)
                    .extracting("code").isEqualTo("TEMPLATE_PROVIDER_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static TemplateCommand command() {
        return new TemplateCommand("delivery_notice", "en_US", "UTILITY", List.of(
                new TemplateComponent(HEADER, HeaderFormat.TEXT, "Delivery update", null, List.of()),
                new TemplateComponent(BODY, null, "Hello $(customer)", null, List.of()),
                new TemplateComponent(FOOTER, null, "Reply STOP", null, List.of()),
                new TemplateComponent(ComponentType.BUTTONS, null, null, null, List.of(
                        new TemplateButton(ButtonType.QUICK_REPLY, "Yes", null, null),
                        new TemplateButton(ButtonType.URL, "Track", "https://example.test/{{1}}", null),
                        new TemplateButton(ButtonType.PHONE_NUMBER, "Call", null, "+123456789")))),
                Map.of("customer", List.of("Ada", "Bea")), null, "client-1");
    }
}
