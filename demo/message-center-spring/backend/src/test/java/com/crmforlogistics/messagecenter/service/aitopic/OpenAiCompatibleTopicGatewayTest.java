package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
    void doesNotForceProviderSpecificJsonResponseFormat() {
        UUID sourceId = UUID.randomUUID();
        var source = new AiTopicModels.SourceItem(sourceId, AiTopicModels.SourceType.MESSAGE, "email",
                Instant.parse("2026-08-01T00:00:00Z"), "inbound", "报价", "请报价");
        var request = OpenAiCompatibleTopicGateway.buildRequest(
                "model", new GenerationInput(List.of(source), List.of(), false), new ObjectMapper().findAndRegisterModules());

        assertThat(request).doesNotContainKey("response_format");
        assertThat(request).containsKeys("model", "messages");
    }

    @Test
    void systemPromptInstructsChineseTopics() {
        var request = OpenAiCompatibleTopicGateway.buildRequest(
                "model", new GenerationInput(List.of(), List.of(), false), new ObjectMapper());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");
        assertThat(messages.get(0).get("role")).isEqualTo("system");
        assertThat((String) messages.get(0).get("content")).contains("Chinese");
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

    @Test
    void classifiesProviderTransportFailuresWithoutUsingExceptionMessages() {
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
    }
}
