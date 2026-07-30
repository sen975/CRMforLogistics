package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeComViewerServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void matchesExternalUserIdExactlyAndUsesTheSharedMsgidLimit() throws Exception {
        String longMsgid = "m".repeat(200);
        Path messages = tempDir.resolve("wecom-messages.jsonl");
        Files.writeString(messages, """
                {"msgid":"%s","secret_key":"secret-exact","external_userid":"Ext-1","userid":"employee-1","send_time":1,"msgtype":"2"}
                {"msgid":"other","secret_key":"secret-other","external_userid":"ext-1","userid":"employee-1","send_time":2,"msgtype":"2"}
                """.formatted(longMsgid), StandardCharsets.UTF_8);
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_DATA_FILE", messages.toString(),
                "WECOM_VIEWER_AUDIT_FILE", tempDir.resolve("audit.jsonl").toString()
        ));
        WeComViewerService service = WeComViewerService.forTests(config, Clock.systemUTC(),
                () -> "nonce", new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "employee-1"));
        WeComViewerService.LoginExchangeResponse login = service.exchangeLoginCode("code");

        WeComViewerService.ViewerSessionResponse created = service.createViewerSession(
                "wecom:Ext-1", login.viewerAuthToken());
        WeComViewerService.ViewerSessionDetail detail = service.viewerSession(
                created.viewerSessionId(), login.viewerAuthToken());

        assertEquals(1, detail.messages().size());
        assertEquals(longMsgid, detail.messages().get(0).msgid());
    }
}
