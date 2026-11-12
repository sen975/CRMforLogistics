package com.crmforlogistics.messagecenter.channel.wecom;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WeComDirectoryGatewayTest {
    private static final ResolvedInstallation INSTALLATION = new ResolvedInstallation(
            "21cc8d72-b5b5-4b2d-a57a-c3752c0603d8", "suite", "corp", "1", "code", 1);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    void mapsMemberDepartmentAndTagReadEndpoints() {
        WeComApiClient client = mock(WeComApiClient.class);
        WeComDirectoryGateway gateway = new WeComDirectoryGateway(client);

        gateway.getMember(INSTALLATION, "member-1", TIMEOUT);
        gateway.listMembers(INSTALLATION, 7, true, TIMEOUT);
        gateway.listDepartments(INSTALLATION, null, TIMEOUT);
        gateway.listDepartments(INSTALLATION, 7L, TIMEOUT);
        gateway.listTags(INSTALLATION, TIMEOUT);
        gateway.getTag(INSTALLATION, 9L, TIMEOUT);

        verify(client).get(INSTALLATION, "/cgi-bin/user/get", Map.of("userid", "member-1"), TIMEOUT);
        verify(client).get(INSTALLATION, "/cgi-bin/user/simplelist",
                Map.of("department_id", 7L, "fetch_child", 1), TIMEOUT);
        verify(client).get(INSTALLATION, "/cgi-bin/department/list", Map.of(), TIMEOUT);
        verify(client).get(INSTALLATION, "/cgi-bin/department/list", Map.of("id", 7L), TIMEOUT);
        verify(client).get(INSTALLATION, "/cgi-bin/tag/list", Map.of(), TIMEOUT);
        verify(client).get(INSTALLATION, "/cgi-bin/tag/get", Map.of("tagid", 9L), TIMEOUT);
    }
}
