package com.crmforlogistics.messagecenter;

import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailRequest;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponseBody;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliyunChatAppTemplateGatewayTest {
    @Test
    void forwardsListFiltersAndPaginationToSdk() throws Exception {
        AtomicReference<ListChatappTemplateRequest> captured = new AtomicReference<>();
        ListChatappTemplateResponseBody body = ListChatappTemplateResponseBody.builder()
                .code("OK")
                .success(true)
                .total(7)
                .listTemplate(List.of())
                .build();
        AsyncClient client = clientProxy(
                request -> {
                    captured.set(request);
                    return CompletableFuture.completedFuture(listResponse(body));
                },
                request -> unexpected("getChatappTemplateDetail"),
                () -> { });
        Config config = new Config(Map.of(
                "CUST_SPACE_ID", "space-1",
                "TEMPLATE_LANGUAGE", "zh_CN",
                "TEMPLATE_NAME", "shipping_notice",
                "TEMPLATE_CODE", "code-1",
                "TEMPLATE_AUDIT_STATUS", "pass",
                "TEMPLATE_CATEGORY", "UTILITY",
                "TEMPLATE_TYPE", "WHATSAPP"));

        ChatAppTemplateGateway.TemplatePage page = new AliyunChatAppTemplateGateway(config, client)
                .listTemplates(3, 25, Duration.ofSeconds(1));

        ListChatappTemplateRequest request = captured.get();
        assertEquals("space-1", request.getCustSpaceId());
        assertEquals("zh_CN", request.getLanguage());
        assertEquals("shipping_notice", request.getName());
        assertEquals("code-1", request.getCode());
        assertEquals("pass", request.getAuditStatus());
        assertEquals("UTILITY", request.getCategory());
        assertEquals("WHATSAPP", request.getTemplateType());
        assertEquals(3, request.getPage().getIndex());
        assertEquals(25, request.getPage().getSize());
        assertEquals(7, page.total());
    }

    @Test
    void forwardsSummaryFieldsToDetailRequest() throws Exception {
        AtomicReference<GetChatappTemplateDetailRequest> captured = new AtomicReference<>();
        GetChatappTemplateDetailResponseBody.Data data = GetChatappTemplateDetailResponseBody.Data.builder()
                .templateCode("code-1")
                .name("shipping_notice")
                .language("zh_CN")
                .build();
        AsyncClient client = clientProxy(
                request -> unexpected("listChatappTemplate"),
                request -> {
                    captured.set(request);
                    return CompletableFuture.completedFuture(detailResponse(
                            GetChatappTemplateDetailResponseBody.builder()
                                    .code("OK")
                                    .data(data)
                                    .build()));
                },
                () -> { });
        ChatAppTemplateGateway.TemplateSummary summary = new ChatAppTemplateGateway.TemplateSummary(
                "code-1", "shipping_notice", "zh_CN", "WHATSAPP");

        new AliyunChatAppTemplateGateway(new Config(Map.of("CUST_SPACE_ID", "space-1")), client)
                .getTemplateDetail(summary, Duration.ofSeconds(1));

        GetChatappTemplateDetailRequest request = captured.get();
        assertEquals("space-1", request.getCustSpaceId());
        assertEquals("code-1", request.getTemplateCode());
        assertEquals("shipping_notice", request.getTemplateName());
        assertEquals("zh_CN", request.getLanguage());
        assertEquals("WHATSAPP", request.getTemplateType());
    }

    @Test
    void cancelsListRequestWhenTimeoutExpires() {
        CompletableFuture<ListChatappTemplateResponse> future = new CompletableFuture<>();
        AsyncClient client = clientProxy(request -> future,
                request -> unexpected("getChatappTemplateDetail"), () -> { });
        AliyunChatAppTemplateGateway gateway = new AliyunChatAppTemplateGateway(
                new Config(Map.of("CUST_SPACE_ID", "space-1")), client);

        assertThrows(TimeoutException.class,
                () -> gateway.listTemplates(1, 10, Duration.ZERO));

        assertTrue(future.isCancelled());
    }

    @Test
    void cancelsDetailRequestAndRestoresInterruptFlag() {
        CompletableFuture<GetChatappTemplateDetailResponse> future = new CompletableFuture<>();
        AsyncClient client = clientProxy(request -> unexpected("listChatappTemplate"),
                request -> future, () -> { });
        AliyunChatAppTemplateGateway gateway = new AliyunChatAppTemplateGateway(
                new Config(Map.of("CUST_SPACE_ID", "space-1")), client);
        ChatAppTemplateGateway.TemplateSummary summary = new ChatAppTemplateGateway.TemplateSummary(
                "code-1", "shipping_notice", "zh_CN", "WHATSAPP");

        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                    () -> gateway.getTemplateDetail(summary, Duration.ofSeconds(1)));
            assertTrue(future.isCancelled());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void reportsListApiNameForFailedResponse() {
        ListChatappTemplateResponseBody body = ListChatappTemplateResponseBody.builder()
                .code("Forbidden")
                .message("denied")
                .success(false)
                .build();
        AsyncClient client = clientProxy(
                request -> CompletableFuture.completedFuture(listResponse(body)),
                request -> unexpected("getChatappTemplateDetail"),
                () -> { });
        AliyunChatAppTemplateGateway gateway = new AliyunChatAppTemplateGateway(
                new Config(Map.of("CUST_SPACE_ID", "space-1")), client);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> gateway.listTemplates(1, 10, Duration.ofSeconds(1)));

        assertTrue(exception.getMessage().contains("ListChatappTemplate"));
    }

    @Test
    void reportsDetailApiNameForFailedResponse() {
        GetChatappTemplateDetailResponseBody body = GetChatappTemplateDetailResponseBody.builder()
                .code("Forbidden")
                .message("denied")
                .build();
        AsyncClient client = clientProxy(
                request -> unexpected("listChatappTemplate"),
                request -> CompletableFuture.completedFuture(detailResponse(body)),
                () -> { });
        AliyunChatAppTemplateGateway gateway = new AliyunChatAppTemplateGateway(
                new Config(Map.of("CUST_SPACE_ID", "space-1")), client);
        ChatAppTemplateGateway.TemplateSummary summary = new ChatAppTemplateGateway.TemplateSummary(
                "code-1", "shipping_notice", "zh_CN", "WHATSAPP");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> gateway.getTemplateDetail(summary, Duration.ofSeconds(1)));

        assertTrue(exception.getMessage().contains("GetChatappTemplateDetail"));
    }

    @Test
    void closesSdkClient() {
        AtomicBoolean closed = new AtomicBoolean();
        AsyncClient client = clientProxy(
                request -> unexpected("listChatappTemplate"),
                request -> unexpected("getChatappTemplateDetail"),
                () -> closed.set(true));

        new AliyunChatAppTemplateGateway(new Config(Map.of()), client).close();

        assertTrue(closed.get());
    }

    @Test
    void projectsTemplateSummaryAndDetailWithAuditState() {
        ListChatappTemplateResponseBody.ListTemplate row =
                ListChatappTemplateResponseBody.ListTemplate.builder()
                        .templateCode("code-1").templateName("shipping_notice")
                        .language("zh_CN").templateType("WHATSAPP").build();
        ChatAppTemplateGateway.TemplateSummary summary =
                AliyunChatAppTemplateGateway.toSummary(row);
        GetChatappTemplateDetailResponseBody.Data detail =
                GetChatappTemplateDetailResponseBody.Data.builder()
                        .templateCode("code-1").name("shipping_notice").language("zh_CN")
                        .auditStatus("pass")
                        .components(List.of(
                                GetChatappTemplateDetailResponseBody.Components.builder()
                                        .type("HEADER").text("Shipping update").build(),
                                GetChatappTemplateDetailResponseBody.Components.builder()
                                        .type("BODY").text("Order $(orderNo)").build()))
                        .build();

        TemplateStore.TemplateRecord record =
                AliyunChatAppTemplateGateway.toRecord(summary, detail);

        assertEquals("code-1", summary.templateCode());
        assertEquals("WHATSAPP", summary.templateType());
        assertEquals("Order $(orderNo)", record.body);
        assertTrue(record.raw.contains("\"auditStatus\":\"pass\""));
    }

    private static ListChatappTemplateResponse listResponse(ListChatappTemplateResponseBody body) {
        return ListChatappTemplateResponse.create().toBuilder().body(body).build();
    }

    private static GetChatappTemplateDetailResponse detailResponse(GetChatappTemplateDetailResponseBody body) {
        return GetChatappTemplateDetailResponse.create().toBuilder().body(body).build();
    }

    private static AsyncClient clientProxy(
            ListRequestCall listCall,
            DetailRequestCall detailCall,
            Runnable closeCall
    ) {
        return (AsyncClient) Proxy.newProxyInstance(
                AsyncClient.class.getClassLoader(),
                new Class<?>[]{AsyncClient.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "listChatappTemplate" -> listCall.call((ListChatappTemplateRequest) arguments[0]);
                    case "getChatappTemplateDetail" ->
                            detailCall.call((GetChatappTemplateDetailRequest) arguments[0]);
                    case "close" -> {
                        closeCall.run();
                        yield null;
                    }
                    default -> throw new AssertionError("Unexpected AsyncClient call: " + method.getName());
                });
    }

    private static <T> T unexpected(String methodName) {
        throw new AssertionError("Unexpected AsyncClient call: " + methodName);
    }

    @FunctionalInterface
    private interface ListRequestCall {
        CompletableFuture<ListChatappTemplateResponse> call(ListChatappTemplateRequest request);
    }

    @FunctionalInterface
    private interface DetailRequestCall {
        CompletableFuture<GetChatappTemplateDetailResponse> call(GetChatappTemplateDetailRequest request);
    }
}
