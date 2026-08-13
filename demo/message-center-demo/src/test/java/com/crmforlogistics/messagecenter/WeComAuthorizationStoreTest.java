package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

class WeComAuthorizationStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void upsertsAndReloadsEncryptedInstallationWithStableId() throws Exception {
        Path file = tempDir.resolve("installations.jsonl");
        CredentialCipher cipher = CredentialCipher.fromBase64Key(key("primary"));
        Clock clock = Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC);
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(file, cipher, clock);

        WeComAuthorizationStore.Installation first = store.upsertActive("dk-suite", "ww-corp", "1000002", "pc-1");
        WeComAuthorizationStore.Installation second = store.upsertActive("dk-suite", "ww-corp", "1000003", "pc-2");

        assertEquals(first.installationId(), second.installationId());
        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertFalse(Files.readString(file).contains("pc-2"));

        WeComAuthorizationStore restarted = WeComAuthorizationStore.forTests(file, cipher, clock);
        WeComAuthorizationStore.ResolvedInstallation resolved = restarted.resolveActive("dk-suite", "ww-corp");
        assertEquals("1000003", resolved.installation().agentId());
        assertEquals("pc-2", resolved.permanentCode());
    }

    @Test
    void revokedInstallationCannotBeResolved() throws Exception {
        CredentialCipher cipher = CredentialCipher.fromBase64Key(key("primary"));
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(
                tempDir.resolve("installations.jsonl"), cipher, Clock.systemUTC());
        store.upsertActive("dk-suite", "ww-corp", "1000002", "pc-1");
        store.updateStatus("dk-suite", "ww-corp", WeComAuthorizationStore.AuthStatus.REVOKED);

        WeComAuthorizationException exception = assertThrows(WeComAuthorizationException.class,
                () -> store.resolveActive("dk-suite", "ww-corp"));
        assertEquals("WECOM_INSTALLATION_INACTIVE", exception.code());
    }

    @Test
    void anyMalformedNonBlankLineDisablesTheStore() throws Exception {
        Path file = tempDir.resolve("installations.jsonl");
        Files.writeString(file, "{not-json}\n", StandardCharsets.UTF_8);
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(file,
                CredentialCipher.fromBase64Key(key("primary")), Clock.systemUTC());

        WeComAuthorizationException exception = assertThrows(WeComAuthorizationException.class,
                () -> store.find("dk-suite", "ww-corp"));
        assertEquals("WECOM_INSTALLATION_STORE_CORRUPTED", exception.code());
    }

    @Test
    void rejectsDuplicateInstallationKeys() throws Exception {
        CredentialCipher cipher = CredentialCipher.fromBase64Key(key("primary"));
        String encrypted = cipher.encrypt(Map.of("permanentCode", "pc-1"));
        String row = "{\"installationId\":\"one\",\"suiteId\":\"dk-suite\",\"authCorpId\":\"ww-corp\","
                + "\"agentId\":\"1000002\",\"permanentCodeEncrypted\":" + quote(encrypted)
                + ",\"authStatus\":\"ACTIVE\",\"authorizedAt\":\"2026-07-28T00:00:00Z\","
                + "\"updatedAt\":\"2026-07-28T00:00:00Z\",\"version\":1}\n"
                + "{\"installationId\":\"two\",\"suiteId\":\"dk-suite\",\"authCorpId\":\"ww-corp\","
                + "\"agentId\":\"1000003\",\"permanentCodeEncrypted\":" + quote(encrypted)
                + ",\"authStatus\":\"ACTIVE\",\"authorizedAt\":\"2026-07-28T00:00:00Z\","
                + "\"updatedAt\":\"2026-07-28T00:00:00Z\",\"version\":2}\n";
        Path file = tempDir.resolve("installations.jsonl");
        Files.writeString(file, row, StandardCharsets.UTF_8);
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(file, cipher, Clock.systemUTC());

        assertEquals("WECOM_INSTALLATION_STORE_CORRUPTED", assertThrows(WeComAuthorizationException.class,
                () -> store.find("dk-suite", "ww-corp")).code());
    }

    @Test
    void appliesAuthorizationEventOnceAndReadsLegacyInstallation() throws Exception {
        Path file = tempDir.resolve("installations.jsonl");
        CredentialCipher cipher = CredentialCipher.fromBase64Key(key("primary"));
        String encrypted = cipher.encrypt(Map.of("permanentCode", "pc-1"));
        Files.writeString(file, legacyRow(encrypted), StandardCharsets.UTF_8);
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(
                file, cipher, Clock.fixed(Instant.parse("2026-08-13T02:00:00Z"), ZoneOffset.UTC));
        Instant eventAt = Instant.parse("2026-08-13T01:00:00Z");

        WeComAuthorizationStore.MutationResult first = store.updateStatusForEvent(
                "dk-suite", "ww-corp", WeComAuthorizationStore.AuthStatus.REVOKED,
                eventId('a'), eventAt);
        WeComAuthorizationStore.MutationResult replay = store.updateStatusForEvent(
                "dk-suite", "ww-corp", WeComAuthorizationStore.AuthStatus.REVOKED,
                eventId('a'), eventAt);

        assertTrue(first.applied());
        assertFalse(replay.applied());
        assertEquals(first.installation().version(), replay.installation().version());
        assertEquals(eventId('a'), replay.installation().lastAuthorizationEventId());
        assertEquals(eventAt, replay.installation().lastAuthorizationEventAt());
    }

    @Test
    void rejectsOlderAuthorizationEventAndPreservesLatestMarker() throws Exception {
        CredentialCipher cipher = CredentialCipher.fromBase64Key(key("primary"));
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(
                tempDir.resolve("installations.jsonl"), cipher,
                Clock.fixed(Instant.parse("2026-08-13T03:00:00Z"), ZoneOffset.UTC));
        store.upsertActiveForEvent("dk-suite", "ww-corp", "1000002", "pc-1",
                eventId('b'), Instant.parse("2026-08-13T02:00:00Z"));

        WeComAuthorizationException stale = assertThrows(WeComAuthorizationException.class,
                () -> store.updateStatusForEvent("dk-suite", "ww-corp",
                        WeComAuthorizationStore.AuthStatus.REVOKED, eventId('d'),
                        Instant.parse("2026-08-13T01:00:00Z")));

        assertEquals("WECOM_AUTHORIZATION_EVENT_STALE", stale.code());
        WeComAuthorizationStore.Installation current = store.find("dk-suite", "ww-corp").orElseThrow();
        assertEquals(eventId('b'), current.lastAuthorizationEventId());
        assertEquals(1, current.version());
    }

    @Test
    void replayedUpsertKeepsOriginalCredentialsAndVersion() throws Exception {
        CredentialCipher cipher = CredentialCipher.fromBase64Key(key("primary"));
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(
                tempDir.resolve("installations.jsonl"), cipher, Clock.systemUTC());
        Instant eventAt = Instant.parse("2026-08-13T01:00:00Z");
        WeComAuthorizationStore.MutationResult first = store.upsertActiveForEvent(
                "dk-suite", "ww-corp", "1000002", "pc-original",
                eventId('c'), eventAt);

        WeComAuthorizationStore.MutationResult replay = store.upsertActiveForEvent(
                "dk-suite", "ww-corp", "9999999", "pc-replayed",
                eventId('c'), eventAt);

        assertFalse(replay.applied());
        assertEquals(first.installation().version(), replay.installation().version());
        assertEquals("1000002", replay.installation().agentId());
        assertEquals("pc-original",
                store.resolveActive("dk-suite", "ww-corp").permanentCode());
    }

    @Test
    void rejectsInstallationWithOnlyOneAuthorizationMarkerField() throws Exception {
        Path file = tempDir.resolve("installations.jsonl");
        CredentialCipher cipher = CredentialCipher.fromBase64Key(key("primary"));
        String row = legacyRow(cipher.encrypt(Map.of("permanentCode", "pc-1"))).trim();
        row = row.substring(0, row.length() - 1)
                + ",\"lastAuthorizationEventId\":\"sha256:event\"}\n";
        Files.writeString(file, row, StandardCharsets.UTF_8);
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(file, cipher, Clock.systemUTC());

        assertEquals("WECOM_INSTALLATION_STORE_CORRUPTED",
                assertThrows(WeComAuthorizationException.class,
                        () -> store.find("dk-suite", "ww-corp")).code());
    }

    @Test
    void rejectsMalformedAuthorizationEventIdWithoutWritingSnapshot() throws Exception {
        Path file = tempDir.resolve("installations.jsonl");
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(file,
                CredentialCipher.fromBase64Key(key("primary")), Clock.systemUTC());

        assertThrows(IllegalArgumentException.class,
                () -> store.upsertActiveForEvent("dk-suite", "ww-corp", "1000002", "pc-1",
                        "sha256:not-a-lowercase-sha256-digest", Instant.parse("2026-08-13T01:00:00Z")));

        assertFalse(Files.exists(file));
    }

    private static String legacyRow(String encrypted) {
        return "{\"installationId\":\"one\",\"suiteId\":\"dk-suite\","
                + "\"authCorpId\":\"ww-corp\",\"agentId\":\"1000002\","
                + "\"permanentCodeEncrypted\":" + quote(encrypted)
                + ",\"authStatus\":\"ACTIVE\",\"authorizedAt\":\"2026-07-28T00:00:00Z\","
                + "\"updatedAt\":\"2026-07-28T00:00:00Z\",\"version\":1}\n";
    }

    private static String key(String seed) {
        byte[] bytes = new byte[32];
        byte[] source = seed.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(source, 0, bytes, 0, Math.min(source.length, bytes.length));
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String eventId(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
