package com.crmforlogistics.messagecenter.channel.chatapp.template;

import com.aliyun.teaopenapi.Client;
import com.aliyun.teaopenapi.models.Params;
import com.aliyun.tea.TeaException;
import com.crmforlogistics.messagecenter.config.AppConfig;
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

import static com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PublicTemplateGatewayContractTest {
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
            assertThat(method.getParameterTypes()).containsExactly(Query.class);
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
        AppConfig config = mock(AppConfig.class);
        Client client = mock(Client.class);
        when(config.custSpaceId()).thenReturn("space-1");
        TeaException providerError = new TeaException(Map.of(
                "code", "MissingMessageCategory",
                "message", "message category is required",
                "data", Map.of(
                        "Code", "MissingMessageCategory",
                        "RequestId", "provider-request-1",
                        "statusCode", 400)));
        when(client.callApi(any(), any(), any())).thenThrow(providerError);
        AliyunChatAppPublicTemplateGateway gateway = new AliyunChatAppPublicTemplateGateway(
                config, new ObjectMapper(), client, Duration.ofSeconds(1));

        assertThatThrownBy(() -> gateway.list(new Query(
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
        AppConfig config = mock(AppConfig.class);
        Client client = mock(Client.class);
        when(config.custSpaceId()).thenReturn("space-1");
        TeaException providerError = new TeaException(Map.of(
                "code", "null",
                "message", "provider rejected request",
                "data", Map.of("RequestId", "provider-request-2", "statusCode", 403)));
        when(client.callApi(any(), any(), any())).thenThrow(providerError);
        AliyunChatAppPublicTemplateGateway gateway = new AliyunChatAppPublicTemplateGateway(
                config, new ObjectMapper(), client, Duration.ofSeconds(1));

        assertThatThrownBy(() -> gateway.list(new Query(
                null, "zh_CN", null, List.of(), List.of(), 1, 20)))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class, failure -> {
                    assertThat(failure.getMessage()).isEqualTo("Public template list failed: HTTP_403");
                    assertThat(failure.providerRequestId()).isEqualTo("provider-request-2");
                });
    }

    @Test
    void fallsBackToRootExceptionTypeForNonTeaFailures() throws Exception {
        AppConfig config = mock(AppConfig.class);
        Client client = mock(Client.class);
        when(config.custSpaceId()).thenReturn("space-1");
        when(client.callApi(any(), any(), any())).thenThrow(
                new IllegalStateException("provider call failed", new SocketException("connection denied")));
        AliyunChatAppPublicTemplateGateway gateway = new AliyunChatAppPublicTemplateGateway(
                config, new ObjectMapper(), client, Duration.ofSeconds(1));

        assertThatThrownBy(() -> gateway.list(new Query(
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
                .isEqualTo(new Class<?>[]{AppConfig.class, ObjectMapper.class});
        assertThat(constructors).anySatisfy(constructor -> {
            assertThat(constructor.getParameterTypes()).containsExactly(AppConfig.class, ObjectMapper.class);
            assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
        });
        assertThat(constructors).anySatisfy(constructor -> assertThat(constructor.getParameterTypes())
                .containsExactly(AppConfig.class, ObjectMapper.class, Client.class, Duration.class));
        assertThat(constructors).noneMatch(constructor -> constructor.getParameterCount() == 4
                && constructor.isAnnotationPresent(Autowired.class));
    }

}
