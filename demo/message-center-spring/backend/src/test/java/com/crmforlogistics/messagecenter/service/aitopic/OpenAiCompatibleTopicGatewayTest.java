package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.net.URI;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLException;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleTopicGatewayTest {
    @Test
    void marksAuditAwareConstructorForSpringInjection() throws NoSuchMethodException {
        var constructor = OpenAiCompatibleTopicGateway.class.getConstructor(
                com.crmforlogistics.messagecenter.config.AiTopicConfig.class,
                ObjectMapper.class,
                AiTopicGenerationAuditService.class);

        assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
    }

    @Test
    void doesNotForceProviderSpecificJsonResponseFormat() {
        UUID sourceId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        var source = new AiTopicModels.SourceItem(sourceId, AiTopicModels.SourceType.MESSAGE, "email",
                Instant.parse("2026-08-01T00:00:00Z"), "inbound", "报价", "请报价");
        var request = OpenAiCompatibleTopicGateway.buildRequest(
                "model", new GenerationInput(new AiTopicOwnerService.OwnerRef("WECOM_GROUP", ownerId), List.of(source), List.of(), false),
                new ObjectMapper().findAndRegisterModules(), 0.65);

        assertThat(request).doesNotContainKey("response_format");
        assertThat(request).containsKeys("model", "messages");
    }

    @Test
    void payloadCarriesTheSingleOwnerScopeAlongsideWeComSummary() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        var source = new AiTopicModels.SourceItem(sourceId, AiTopicModels.SourceType.WECOM_SUMMARY, "wecom",
                Instant.parse("2026-08-01T00:00:00Z"), "inbound", "", "官方会话概要");
        var request = OpenAiCompatibleTopicGateway.buildRequest(
                "model", new GenerationInput(new AiTopicOwnerService.OwnerRef("WECOM_GROUP", ownerId), List.of(source), List.of(), false),
                new ObjectMapper().findAndRegisterModules(), 0.65);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");
        var payload = new ObjectMapper().readTree((String) messages.get(1).get("content"));

        assertThat(payload.path("owner").path("type").asText()).isEqualTo("WECOM_GROUP");
        assertThat(payload.path("owner").path("id").asText()).isEqualTo(ownerId.toString());
        assertThat(payload.path("sources").get(0).path("channelType").asText()).isEqualTo("wecom");
        assertThat(payload.path("sources").get(0).path("text").asText()).isEqualTo("官方会话概要");
    }

    @Test
    void systemPromptInstructsChineseTopics() {
        var request = OpenAiCompatibleTopicGateway.buildRequest(
                "model", new GenerationInput(new AiTopicOwnerService.OwnerRef("CONTACT", UUID.randomUUID()), List.of(), List.of(), false),
                new ObjectMapper(), 0.65);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");
        assertThat(messages.get(0).get("role")).isEqualTo("system");
        assertThat((String) messages.get(0).get("content")).contains("Chinese", "WeCom summaries");
    }

    @Test
    void systemPromptAllowsWhatsAppSourceMessages() {
        var request = OpenAiCompatibleTopicGateway.buildRequest(
                "model", new GenerationInput(new AiTopicOwnerService.OwnerRef("CONTACT", UUID.randomUUID()), List.of(), List.of(), false),
                new ObjectMapper(), 0.65);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");

        assertThat((String) messages.get(0).get("content")).contains("WhatsApp");
    }

    @Test
    void systemPromptTiesExistingTopicReferencesToConfiguredThreshold() {
        var request = OpenAiCompatibleTopicGateway.buildRequest(
                "model", new GenerationInput(new AiTopicOwnerService.OwnerRef("CONTACT", UUID.randomUUID()), List.of(), List.of(), false),
                new ObjectMapper(), 0.65);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");
        String content = (String) messages.get(0).get("content");

        assertThat(content).contains("relevance is at least 0.65");
        assertThat(content).contains("below 0.65");
        assertThat(content).contains("create a new topic with a new key instead");
        assertThat(content).contains("never reference the existing topic's UUID with a low relevance");
    }

    @Test
    void systemPromptReflectsLowerConfiguredThreshold() {
        var request = OpenAiCompatibleTopicGateway.buildRequest(
                "model", new GenerationInput(new AiTopicOwnerService.OwnerRef("CONTACT", UUID.randomUUID()), List.of(), List.of(), false),
                new ObjectMapper(), 0.4);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");
        assertThat((String) messages.get(0).get("content")).contains("relevance is at least 0.40", "below 0.40");
    }

    @Test
    void resolvesCommonCompatibleEndpointShapes() {
        assertThat(OpenAiCompatibleTopicGateway.resolveEndpoint("https://api.openai.com"))
                .isEqualTo(URI.create("https://api.openai.com/v1/chat/completions"));
        assertThat(OpenAiCompatibleTopicGateway.resolveEndpoint("https://dashscope.example/compatible-mode/v1/"))
                .isEqualTo(URI.create("https://dashscope.example/compatible-mode/v1/chat/completions"));
        assertThat(OpenAiCompatibleTopicGateway.resolveEndpoint("https://gateway.example/openai/v1/chat/completions"))
                .isEqualTo(URI.create("https://gateway.example/openai/v1/chat/completions"));
    }

    @Test
    void mapsRejectedStatusesWithoutExposingProviderResponseBodies() {
        assertThat(OpenAiCompatibleTopicGateway.rejectionCode(400)).isEqualTo("AI_PROVIDER_REQUEST_INVALID");
        assertThat(OpenAiCompatibleTopicGateway.rejectionCode(401)).isEqualTo("AI_PROVIDER_AUTH_FAILED");
        assertThat(OpenAiCompatibleTopicGateway.rejectionCode(403)).isEqualTo("AI_PROVIDER_AUTH_FAILED");
        assertThat(OpenAiCompatibleTopicGateway.rejectionCode(404)).isEqualTo("AI_PROVIDER_ENDPOINT_OR_MODEL_NOT_FOUND");
        assertThat(OpenAiCompatibleTopicGateway.rejectionCode(422)).isEqualTo("AI_PROVIDER_REQUEST_INVALID");
    }

    /**
     * 分类口径，2026-09-30 收窄过一次，改前叫
     * {@code classifiesProviderTransportFailuresWithoutUsingExceptionMessages}。
     *
     * <p><b>当初那条边界在拦什么</b>：异常消息可能含地址、TLS 细节、供应商原文，所以分类结果
     * 绝不能把消息带出去 —— 这一点<b>原样保留</b>（见末尾三条 {@code doesNotContain} 断言）。
     *
     * <p><b>为什么必须收窄</b>：只按类型判表达不了「socket 读超时」——
     * {@code SocketDispatcher.read0} 抛的是裸 {@code java.io.IOException: Operation timed out}，
     * 既不是 {@code HttpTimeoutException} 也不是 {@code SocketTimeoutException}，于是掉进兜底的
     * {@code CLIENT_ERROR}，而这里的分诊码会落进审计表。现在额外认<b>一个</b>由内核产出的固定串
     * {@code "Operation timed out"}；供应商可控的任意文本仍然不参与分类。
     */
    @Test
    void classifiesProviderTransportFailuresByTypeAndNeverEchoesMessages() {
        assertThat(OpenAiCompatibleTopicGateway.providerDiagnostic(new UnknownHostException("secret-host")))
                .isEqualTo("DNS_ERROR");
        assertThat(OpenAiCompatibleTopicGateway.providerDiagnostic(new ConnectException("secret-host")))
                .isEqualTo("CONNECT_ERROR");
        assertThat(OpenAiCompatibleTopicGateway.providerDiagnostic(new HttpTimeoutException("secret-host")))
                .isEqualTo("TIMEOUT");
        assertThat(OpenAiCompatibleTopicGateway.providerDiagnostic(new SSLException("secret-certificate")))
                .isEqualTo("TLS_ERROR");
        assertThat(OpenAiCompatibleTopicGateway.providerDiagnostic(new IllegalStateException("secret-response")))
                .isEqualTo("CLIENT_ERROR");

        // 新增：内核级读超时（ETIMEDOUT）是裸 IOException，必须判成 TIMEOUT 而不是 CLIENT_ERROR。
        assertThat(OpenAiCompatibleTopicGateway.providerDiagnostic(new IOException("Operation timed out")))
                .isEqualTo("TIMEOUT");
        // 包在外层异常里也要认（真实的形状是 ResourceAccessException 套 IOException）。
        assertThat(OpenAiCompatibleTopicGateway.providerDiagnostic(
                new IllegalStateException("wrapped", new IOException("Operation timed out"))))
                .isEqualTo("TIMEOUT");
        // 同样是 IOException、但消息不是内核超时串的，仍然落兜底 —— 收窄只针对一个固定串。
        assertThat(OpenAiCompatibleTopicGateway.providerDiagnostic(new IOException("Connection reset by peer")))
                .isEqualTo("CLIENT_ERROR");
        // 供应商把消息伪装成超时串也没用：它来自 HTTP 响应体，不是这个类型。
        assertThat(OpenAiCompatibleTopicGateway.providerDiagnostic(
                new IllegalStateException("Operation timed out")))
                .isEqualTo("CLIENT_ERROR");

        // 消息永不外带：诊断码本身既不含消息，也不含被塞进消息里的任何东西。
        for (String diagnostic : List.of("DNS_ERROR", "CONNECT_ERROR", "TIMEOUT", "TLS_ERROR", "CLIENT_ERROR")) {
            assertThat(diagnostic)
                    .doesNotContain("secret")
                    .doesNotContain("Operation timed out")
                    .doesNotContain("reset");
        }
    }
}
