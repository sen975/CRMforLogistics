package com.crmforlogistics.messagecenter;

import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponseBody;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliyunChatAppTemplateGatewayTest {
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
}
