package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
