package com.crmforlogistics.messagecenter.channel.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComAppChatGatewayTest {
    private static final ResolvedInstallation INSTALLATION = new ResolvedInstallation(
            "21cc8d72-b5b5-4b2d-a57a-c3752c0603d8", "suite", "corp", "1", "code", 1);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsCreateGetAndUpdateToOfficialContracts() {
        WeComApiClient client = mock(WeComApiClient.class);
        when(client.post(eq(INSTALLATION), eq("/cgi-bin/appchat/create"),
                org.mockito.ArgumentMatchers.any(), eq(TIMEOUT)))
                .thenReturn(objectMapper.createObjectNode().put("chatid", "chat-1"));
        WeComAppChatGateway gateway = new WeComAppChatGateway(client, objectMapper);

        gateway.create(INSTALLATION, "chat-1", "项目群", "owner-1",
                List.of("owner-1", "member-2"), TIMEOUT);
        gateway.get(INSTALLATION, "chat-1", TIMEOUT);
        gateway.update(INSTALLATION, "chat-1", "新名称", "owner-2",
                List.of("member-3"), List.of("member-2"), TIMEOUT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(client).post(eq(INSTALLATION), eq("/cgi-bin/appchat/create"), body.capture(), eq(TIMEOUT));
        assertThat(body.getValue()).containsEntry("chatid", "chat-1")
                .containsEntry("name", "项目群").containsEntry("owner", "owner-1")
                .containsEntry("userlist", List.of("owner-1", "member-2"));
        verify(client).get(INSTALLATION, "/cgi-bin/appchat/get", Map.of("chatid", "chat-1"), TIMEOUT);

        verify(client).post(eq(INSTALLATION), eq("/cgi-bin/appchat/update"), body.capture(), eq(TIMEOUT));
        assertThat(body.getValue()).containsEntry("add_user_list", List.of("member-3"))
                .containsEntry("del_user_list", List.of("member-2"));
    }

    @Test
    void mapsMessageTypeToMatchingContentObject() {
        WeComApiClient client = mock(WeComApiClient.class);
        WeComAppChatGateway gateway = new WeComAppChatGateway(client, objectMapper);
        JsonNode content = objectMapper.createObjectNode().put("content", "hello");

        gateway.send(INSTALLATION, "chat-1", "text", content, true, TIMEOUT);

        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(client).post(eq(INSTALLATION), eq("/cgi-bin/appchat/send"), body.capture(), eq(TIMEOUT));
        assertThat(body.getValue().path("chatid").asText()).isEqualTo("chat-1");
        assertThat(body.getValue().path("msgtype").asText()).isEqualTo("text");
        assertThat(body.getValue().path("text").path("content").asText()).isEqualTo("hello");
        assertThat(body.getValue().path("safe").asInt()).isEqualTo(1);
    }
}
