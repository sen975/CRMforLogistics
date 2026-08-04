package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalWeComDevelopmentServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void fixtureCompletesLocalLoginSyncAndOneTimeSession() throws Exception {
        Config config = localConfig(Map.of("LOCAL_WECOM_DATA_SOURCE", "fixture"));
        LocalWeComDevelopmentService service = new LocalWeComDevelopmentService(config);

        var attempt = service.createAttempt();
        assertEquals("http://localhost:8099/", attempt.redirectUri());
        var login = service.exchange("local-code", attempt.state());
        var sync = service.sync(login.viewerAuthToken());

        assertEquals(1, sync.pages());
        assertEquals(3, sync.stored());
        var created = service.createSession("wecom:local-contact-001", login.viewerAuthToken());
        var detail = service.readSession(created.viewerSessionId(), login.viewerAuthToken());
        assertEquals(2, detail.messages().size());
        assertEquals("local-corp", detail.corpId());
        assertThrows(SecurityException.class,
                () -> service.readSession(created.viewerSessionId(), login.viewerAuthToken()));
        assertFalse(Files.readString(config.wecomDataFile()).isBlank());
    }

    @Test
    void jsonlSourceIsProjectedToTargetAndMalformedRowsAreIgnored() throws Exception {
        Path source = tempDir.resolve("source.jsonl");
        Files.writeString(source,
                "{\"msgid\":\"jsonl-1\",\"external_userid\":\"external-1\",\"userid\":\"owner\","
                        + "\"send_time\":10,\"secret_key\":\"secret-1\",\"msgtype\":\"text\"}\n"
                        + "not-json\n");
        Config config = localConfig(Map.of(
                "LOCAL_WECOM_DATA_SOURCE", "jsonl",
                "LOCAL_WECOM_DATA_FILE", source.toString()));
        LocalWeComDevelopmentService service = new LocalWeComDevelopmentService(config);
        var login = service.exchange("code", service.createAttempt().state());
        var sync = service.sync(login.viewerAuthToken());

        assertEquals(1, sync.stored());
        var session = service.createSession("wecom:external-1", login.viewerAuthToken());
        assertEquals(1, service.readSession(session.viewerSessionId(), login.viewerAuthToken()).messages().size());
    }

    private Config localConfig(Map<String, String> extra) {
        var values = new java.util.HashMap<String, String>();
        values.put("LOCAL_DEV_MODE", "true");
        values.put("DATA_DIR", tempDir.resolve("data").toString());
        values.put("LOCAL_WECOM_DATA_FILE", tempDir.resolve("local-source.jsonl").toString());
        values.put("WECOM_DATA_FILE", tempDir.resolve("data/messages.jsonl").toString());
        values.putAll(extra);
        return new Config(values);
    }
}
