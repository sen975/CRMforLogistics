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

class WeComExternalContactGatewayTest {
    private static final ResolvedInstallation INSTALLATION = new ResolvedInstallation(
            "21cc8d72-b5b5-4b2d-a57a-c3752c0603d8", "suite", "corp", "1", "code", 1);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    void mapsContactAndGroupOperationsToOfficialPaths() {
        WeComApiClient client = mock(WeComApiClient.class);
        WeComExternalContactGateway gateway = new WeComExternalContactGateway(client);

        gateway.list(INSTALLATION, "member-1", TIMEOUT);
        gateway.get(INSTALLATION, "external-1", "cursor-1", TIMEOUT);
        gateway.batchGet(INSTALLATION, List.of("member-1"), "cursor-2", 10, TIMEOUT);
        gateway.remark(INSTALLATION, "member-1", "external-1", "重点客户", "描述",
                "Acme", List.of("+8613800000000"), TIMEOUT);
        gateway.groupList(INSTALLATION, 0, List.of("member-1"), "cursor-3", 20, TIMEOUT);
        gateway.groupGet(INSTALLATION, "group-1", true, TIMEOUT);

        verify(client).get(INSTALLATION, "/cgi-bin/externalcontact/list",
                Map.of("userid", "member-1"), TIMEOUT);
        verify(client).get(INSTALLATION, "/cgi-bin/externalcontact/get",
                Map.of("external_userid", "external-1", "cursor", "cursor-1"), TIMEOUT);
        verify(client).post(eq(INSTALLATION), eq("/cgi-bin/externalcontact/batch/get_by_user"),
                eq(Map.of("userid_list", List.of("member-1"), "cursor", "cursor-2", "limit", 10)), eq(TIMEOUT));
        verify(client).post(eq(INSTALLATION), eq("/cgi-bin/externalcontact/groupchat/get"),
                eq(Map.of("chat_id", "group-1", "need_name", 1)), eq(TIMEOUT));
    }

    @Test
    void omitsOptionalCursorAndEmptyRemarkFields() {
        WeComApiClient client = mock(WeComApiClient.class);
        WeComExternalContactGateway gateway = new WeComExternalContactGateway(client);

        gateway.get(INSTALLATION, "external-1", "", TIMEOUT);
        gateway.remark(INSTALLATION, "member-1", "external-1", "", null,
                null, List.of(), TIMEOUT);

        verify(client).get(INSTALLATION, "/cgi-bin/externalcontact/get",
                Map.of("external_userid", "external-1"), TIMEOUT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(client).post(eq(INSTALLATION), eq("/cgi-bin/externalcontact/remark"), body.capture(), eq(TIMEOUT));
        assertThat(body.getValue()).containsEntry("userid", "member-1")
                .containsEntry("external_userid", "external-1")
                .doesNotContainKeys("description", "remark_company", "remark_mobiles");
    }
}
