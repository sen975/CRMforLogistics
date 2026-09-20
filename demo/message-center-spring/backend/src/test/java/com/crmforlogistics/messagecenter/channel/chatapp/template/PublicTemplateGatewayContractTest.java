package com.crmforlogistics.messagecenter.channel.chatapp.template;

import com.aliyun.teaopenapi.Client;
import com.aliyun.teaopenapi.models.Params;
import com.aliyun.tea.TeaException;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsResolver;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.ChatAppPublicTemplateGateway;
import com.crmforlogistics.messagecenter.service.whatsapp.template.TemplateCredentialSource;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.SocketException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.crmforlogistics.messagecenter.service.whatsapp.template.ChatAppPublicTemplateGateway;

class PublicTemplateGatewayContractTest {
    private static final TemplateCredentialSource SOURCE =
            TemplateCredentialSource.space(UUID.fromString("00000000-0000-0000-0000-000000000007"));

    @Test
    void buildsOnlyTheConfirmedListBaseTemplateQuery() throws Exception {
        Map<String, String> query = AliyunChatAppPublicTemplateGateway.listQuery(
                new Query("order", "zh_CN", "UTILITY", List.of("E_COMMERCE"),
                        List.of("ORDER_MANAGEMENT"), 2, 50), "space-1");

        assertThat(query).containsExactlyInAnyOrderEntriesOf(Map.of(
                "CustSpaceId", "space-1",
                "Name", "order",
                "Language", "zh_CN",
                "UseType", "private",
                "MessageCategory", "WHATSAPP",
                "Page.Size", "50",
                "Page.Index", "2",
                "Category", "UTILITY",
                "Industries", "[\"E_COMMERCE\"]",
                "Usecases", "[\"ORDER_MANAGEMENT\"]"));
        assertThat(query).doesNotContainKeys("custSpaceId", "name", "language", "useType", "messageCategory",
                "page.Size", "page.Index", "category", "industries", "usecases", "Page", "HetuParams",
                "hetuUrl", "instanceId", "aliLocale",
                "sec_token", "Cookie", "_fetcher_");
    }

    @Test
    void publicGatewayExposesOnlyList() {
        List<Method> methods = Arrays.asList(ChatAppPublicTemplateGateway.class.getDeclaredMethods());

        assertThat(methods).singleElement().satisfies(method -> {
            assertThat(method.getName()).isEqualTo("list");
            assertThat(method.getParameterTypes()).containsExactly(TemplateCredentialSource.class, Query.class);
            assertThat(method.getReturnType()).isEqualTo(
                    com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Page.class);
        });
    }

    @Test
    void aliyunGatewayDoesNotRetainCopyActions() {
        assertThat(Arrays.stream(AliyunChatAppPublicTemplateGateway.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("copy", "copyQuery");
    }

    @Test
    void providerParamsAcceptOnlyListBaseTemplateGet() throws Exception {
        Method paramsMethod = AliyunChatAppPublicTemplateGateway.class.getDeclaredMethod("params", String.class);
        paramsMethod.setAccessible(true);

        Params listParams = (Params) paramsMethod.invoke(null, "ListBaseTemplate");

        assertThat(listParams.getMethod()).isEqualTo("GET");
        assertThat(listParams.getPathname()).isEqualTo("/");
        assertThatThrownBy(() -> paramsMethod.invoke(null, "CopyTemplate"))
                .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesSanitizedProviderDiagnosticsWhenListBaseTemplateIsRejected() throws Exception {
        Client client = mock(Client.class);
        TeaException providerError = new TeaException(Map.of(
                "code", "MissingMessageCategory",
                "message", "message category is required",
                "data", Map.of(
                        "Code", "MissingMessageCategory",
                        "RequestId", "provider-request-1",
                        "statusCode", 400)));
        when(client.callApi(any(), any(), any())).thenThrow(providerError);
        AliyunChatAppPublicTemplateGateway gateway = new AliyunChatAppPublicTemplateGateway(
                new ObjectMapper(), client, Duration.ofSeconds(1), "space-1");

        assertThatThrownBy(() -> gateway.list(SOURCE, new Query(
                null, "zh_CN", null, List.of(), List.of(), 1, 20)))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PUBLIC_TEMPLATE_LIST_FAILED");
                    assertThat(failure.getMessage()).isEqualTo(
                            "Public template list failed: MissingMessageCategory");
                    assertThat(failure.providerRequestId()).isEqualTo("provider-request-1");
                });
    }

    @Test
    void fallsBackToProviderHttpStatusWhenTeaErrorCodeIsMissing() throws Exception {
        Client client = mock(Client.class);
        TeaException providerError = new TeaException(Map.of(
                "code", "null",
                "message", "provider rejected request",
                "data", Map.of("RequestId", "provider-request-2", "statusCode", 403)));
        when(client.callApi(any(), any(), any())).thenThrow(providerError);
        AliyunChatAppPublicTemplateGateway gateway = new AliyunChatAppPublicTemplateGateway(
                new ObjectMapper(), client, Duration.ofSeconds(1), "space-1");

        assertThatThrownBy(() -> gateway.list(SOURCE, new Query(
                null, "zh_CN", null, List.of(), List.of(), 1, 20)))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class, failure -> {
                    assertThat(failure.getMessage()).isEqualTo("Public template list failed: HTTP_403");
                    assertThat(failure.providerRequestId()).isEqualTo("provider-request-2");
                });
    }

    @Test
    void fallsBackToRootExceptionTypeForNonTeaFailures() throws Exception {
        Client client = mock(Client.class);
        when(client.callApi(any(), any(), any())).thenThrow(
                new IllegalStateException("provider call failed", new SocketException("connection denied")));
        AliyunChatAppPublicTemplateGateway gateway = new AliyunChatAppPublicTemplateGateway(
                new ObjectMapper(), client, Duration.ofSeconds(1), "space-1");

        assertThatThrownBy(() -> gateway.list(SOURCE, new Query(
                null, "zh_CN", null, List.of(), List.of(), 1, 20)))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class, failure ->
                        assertThat(failure.getMessage()).isEqualTo("Public template list failed: SocketException"));
    }

    @Test
    void exposesOnlyTheProductionSpringConstructorForExistingBeans() {
        List<Constructor<?>> constructors = Arrays.asList(AliyunChatAppPublicTemplateGateway.class.getDeclaredConstructors());

        assertThat(constructors.stream().filter(constructor -> java.lang.reflect.Modifier.isPublic(constructor.getModifiers())))
                .singleElement()
                .extracting(Constructor::getParameterTypes)
                .isEqualTo(new Class<?>[]{ObjectMapper.class, ChannelAccountMapper.class,
                        WhatsAppProviderScopeMapper.class, ChatAppAccountCredentialsResolver.class});
        assertThat(constructors).anySatisfy(constructor -> {
            assertThat(constructor.getParameterTypes()).containsExactly(ObjectMapper.class,
                    ChannelAccountMapper.class, WhatsAppProviderScopeMapper.class,
                    ChatAppAccountCredentialsResolver.class);
            assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
        });
        assertThat(constructors).anySatisfy(constructor -> assertThat(constructor.getParameterTypes())
                .containsExactly(ObjectMapper.class, Client.class, Duration.class, String.class));
        assertThat(constructors.stream()
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class)))
                .singleElement()
                .extracting(Constructor::getParameterTypes)
                .isEqualTo(new Class<?>[]{ObjectMapper.class, ChannelAccountMapper.class,
                        WhatsAppProviderScopeMapper.class, ChatAppAccountCredentialsResolver.class});
    }

    @Test
    void productionGatewayResolvesCredentialsForTheRequestedAccount() throws Exception {
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        ChatAppAccountCredentialsResolver credentialsResolver = mock(ChatAppAccountCredentialsResolver.class);
        when(accountMapper.selectById(accountId)).thenReturn(account);
        when(credentialsResolver.resolve(account)).thenReturn(new ChatAppAccountCredentials(
                "account-ak", "account-sk", "account-space", "60111111111",
                "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com"));

        AliyunChatAppPublicTemplateGateway gateway = new AliyunChatAppPublicTemplateGateway(
                new ObjectMapper(), accountMapper, mock(WhatsAppProviderScopeMapper.class),
                credentialsResolver);

        Method credentialsMethod = AliyunChatAppPublicTemplateGateway.class
                .getDeclaredMethod("credentials", TemplateCredentialSource.class);
        credentialsMethod.setAccessible(true);
        ChatAppAccountCredentials credentials = (ChatAppAccountCredentials) credentialsMethod.invoke(
                gateway, TemplateCredentialSource.account(accountId));

        assertThat(credentials.custSpaceId()).isEqualTo("account-space");
        org.mockito.Mockito.verify(credentialsResolver).resolve(account);
    }

    @Test
    void mapsUnreadableAccountCredentialsToStableNonRetryableError() {
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        ChatAppAccountCredentialsResolver credentialsResolver = mock(ChatAppAccountCredentialsResolver.class);
        when(accountMapper.selectById(accountId)).thenReturn(account);
        when(credentialsResolver.resolve(account)).thenThrow(
                new com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsException(
                        "CHATAPP_ACCOUNT_CREDENTIALS_UNREADABLE"));
        AliyunChatAppPublicTemplateGateway gateway = new AliyunChatAppPublicTemplateGateway(
                new ObjectMapper(), accountMapper, mock(WhatsAppProviderScopeMapper.class),
                credentialsResolver);

        assertThatThrownBy(() -> gateway.list(TemplateCredentialSource.account(accountId), new Query(
                null, "zh_CN", null, List.of(), List.of(), 1, 20)))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("CHATAPP_ACCOUNT_CREDENTIALS_UNREADABLE");
                    assertThat(failure.retryable()).isFalse();
                });
    }

}
