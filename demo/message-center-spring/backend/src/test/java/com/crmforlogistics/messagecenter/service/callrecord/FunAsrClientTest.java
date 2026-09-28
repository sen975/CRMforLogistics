package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.config.FunAsrConfig;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FunAsrClientTest {
    private final FunAsrClient client = new FunAsrClient(new FunAsrConfig(
            "http://127.0.0.1:8000", "sensevoice",
            Duration.ofSeconds(3), Duration.ofSeconds(30), 8));

    @Test
    void boundedResponseReaderRejectsPayloadAboveConfiguredLimit() {
        assertThatThrownBy(() -> client.readBounded(new ByteArrayInputStream(new byte[9])))
                .isInstanceOf(CallRecordException.class)
                .hasMessage("FunASR response exceeds the configured size limit");
    }

    @Test
    void usesHttp11BecauseFunAsrDoesNotAcceptTheH2cUpgradeRequest() throws Exception {
        var field = FunAsrClient.class.getDeclaredField("client");
        field.setAccessible(true);
        HttpClient httpClient = (HttpClient) field.get(client);

        assertThat(httpClient.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }

    @Test
    void acceptsTextWithoutSegmentsAndUsesTrustedAudioDuration() {
        byte[] response = """
                {"text":"完整转录文本","segments":[],"language":"auto",
                 "duration":3.11,"model":"sensevoice"}
                """.getBytes(StandardCharsets.UTF_8);

        CallRecordStateMachine.TranscriptionResult result =
                client.parseResponse(response, 62.54);

        assertThat(result.originalText()).isEqualTo("完整转录文本");
        assertThat(result.durationSeconds()).isEqualTo(62.54);
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void validatesSegmentsAgainstTrustedAudioDurationInsteadOfProviderLatency() {
        byte[] response = """
                {"text":"完整转录文本","segments":[
                  {"start":0.0,"end":60.0,"text":"完整转录文本"}
                 ],"language":"auto","duration":3.11,"model":"sensevoice"}
                """.getBytes(StandardCharsets.UTF_8);

        CallRecordStateMachine.TranscriptionResult result =
                client.parseResponse(response, 62.54);

        assertThat(result.durationSeconds()).isEqualTo(62.54);
        assertThat(result.segments()).singleElement()
                .satisfies(segment -> {
                    assertThat(segment.startSeconds()).isZero();
                    assertThat(segment.endSeconds()).isEqualTo(60.0);
                    assertThat(segment.text()).isEqualTo("完整转录文本");
                });
    }

    @Test
    void rejectsResponseWithoutSegmentsField() {
        byte[] response = """
                {"text":"完整转录文本","duration":3.11,"model":"sensevoice"}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> client.parseResponse(response, 62.54))
                .isInstanceOf(CallRecordException.class)
                .hasMessage("FunASR segments are missing");
    }

    @Test
    void rejectsResponseWithNonArraySegments() {
        byte[] response = """
                {"text":"完整转录文本","segments":{},"duration":3.11,
                 "model":"sensevoice"}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> client.parseResponse(response, 62.54))
                .isInstanceOf(CallRecordException.class)
                .hasMessage("FunASR segments are missing");
    }

    @Test
    void rejectsSegmentOutsideTrustedAudioDuration() {
        byte[] response = """
                {"text":"完整转录文本","segments":[
                  {"start":0.0,"end":64.0,"text":"完整转录文本"}
                 ],"language":"auto","duration":3.11,"model":"sensevoice"}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> client.parseResponse(response, 62.54))
                .isInstanceOf(CallRecordException.class)
                .hasMessage("FunASR segment timing is invalid");
    }
}
