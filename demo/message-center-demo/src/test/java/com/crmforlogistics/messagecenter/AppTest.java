package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppTest {
    @TempDir
    Path tempDir;

    @Test
    void fetchesAccessTokenFromTheSelectedActiveInstallation() throws Exception {
        byte[] key = new byte[32];
        String encodedKey = Base64.getEncoder().encodeToString(key);
        CredentialCipher cipher = CredentialCipher.fromBase64Key(encodedKey);
        Path installations = tempDir.resolve("installations.jsonl");
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(installations, cipher,
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC));
        store.upsertActive("suite", "corp-a", "1001", "permanent-code");
        Config config = new Config(Map.of("WECOM_SUITE_ID", "suite"));
        WeComAccessTokenService accessTokens = WeComAccessTokenService.forTests(config,
                Clock.systemUTC(), (corpId, secret, timeout) -> {
                    assertEquals("corp-a", corpId);
                    assertEquals("permanent-code", secret);
                    return new WeComAuthorizationGateway.CorpTokenResponse("debug-token", 7200);
                });

        assertEquals("debug-token", App.fetchWeComAccessToken(config, "corp-a", store, accessTokens));
    }

    @Test
    void rejectsMissingSuiteOrCorpIdBeforeFetchingToken() throws Exception {
        Config config = new Config(Map.of());
        WeComAccessTokenService accessTokens = WeComAccessTokenService.forTests(config,
                Clock.systemUTC(), (corpId, secret, timeout) ->
                        new WeComAuthorizationGateway.CorpTokenResponse("unexpected", 7200));
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(
                tempDir.resolve("installations.jsonl"),
                CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])),
                Clock.systemUTC());

        assertThrows(IllegalArgumentException.class,
                () -> App.fetchWeComAccessToken(config, "corp-a", store, accessTokens));
        Config configured = new Config(Map.of("WECOM_SUITE_ID", "suite"));
        assertThrows(IllegalArgumentException.class,
                () -> App.fetchWeComAccessToken(configured, "", store, accessTokens));
    }

    @Test
    void debugTokenDoesNotRequireAuthorizationInstallation() throws Exception {
        Config config = new Config(Map.of(
                "WECOM_DEBUG_CORP_ID", "ww-debug-corp",
                "WECOM_DEBUG_CORP_SECRET", "permanent-code"));

        assertEquals("debug-access-token", App.fetchDebugWeComAccessToken(config,
                (corpId, secret, timeout) -> {
                    assertEquals("ww-debug-corp", corpId);
                    assertEquals("permanent-code", secret);
                    return new WeComAuthorizationGateway.CorpTokenResponse("debug-access-token", 7200);
                }));
    }

    @Test
    void auditStatusPrintsOneJsonDocumentAndReturnsContractExitCode() throws Exception {
        Config config = new Config(Map.of("DATA_DIR", tempDir.resolve("audit-data").toString()));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = App.run(new String[]{"audit-status"}, config,
                new PrintStream(stdout), new PrintStream(stderr));

        String output = stdout.toString(StandardCharsets.UTF_8).trim();
        JsonObject json = JsonParser.parseString(output).getAsJsonObject();
        assertEquals(0, exit);
        assertEquals("healthy", json.get("status").getAsString());
        assertEquals(2, json.getAsJsonArray("streams").size());
        JsonObject viewer = json.getAsJsonArray("streams").get(0).getAsJsonObject();
        assertTrue(viewer.has("oldestStaleOpenAttemptAt"));
        assertTrue(viewer.get("oldestStaleOpenAttemptAt").isJsonNull());
        assertEquals(output.length(), output.strip().length());
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void auditStatusRejectsAdditionalArgumentsWithOneFailedJsonDocument() throws Exception {
        Config config = new Config(Map.of("DATA_DIR", tempDir.resolve("audit-data").toString()));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exit = App.run(new String[]{"audit-status", "unexpected"}, config,
                new PrintStream(stdout), System.err);

        JsonObject json = JsonParser.parseString(
                stdout.toString(StandardCharsets.UTF_8).trim()).getAsJsonObject();
        assertEquals(3, exit);
        assertEquals("failed", json.get("status").getAsString());
        assertEquals("AUDIT_STATUS_ARGUMENT_INVALID",
                json.getAsJsonArray("issues").get(0).getAsJsonObject().get("code").getAsString());
        assertEquals(1, json.getAsJsonArray("issues").size());
    }

    @Test
    void auditStatusMapsConfigurationFailureToSanitizedStdoutJson() throws Exception {
        Path env = tempDir.resolve("legacy.env");
        Files.writeString(env, "WECOM_VIEWER_AUDIT_MAX_BYTES=1048576\n",
                StandardCharsets.UTF_8);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = App.run(new String[]{"audit-status"}, env,
                new PrintStream(stdout), new PrintStream(stderr));

        String output = stdout.toString(StandardCharsets.UTF_8).trim();
        JsonObject json = JsonParser.parseString(output).getAsJsonObject();
        assertEquals(3, exit);
        assertEquals("failed", json.get("status").getAsString());
        assertEquals("AUDIT_CONFIGURATION_INVALID",
                json.getAsJsonArray("issues").get(0).getAsJsonObject().get("code").getAsString());
        assertFalse(output.contains("WECOM_VIEWER_AUDIT_MAX_BYTES"));
        assertFalse(output.contains("Exception"));
        assertFalse(output.contains("at com."));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }
}
