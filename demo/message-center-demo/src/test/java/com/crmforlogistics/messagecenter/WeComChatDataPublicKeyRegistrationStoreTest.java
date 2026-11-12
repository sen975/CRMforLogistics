package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComChatDataPublicKeyRegistrationStoreTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void atomicallyPersistsOnlySanitizedIdempotencyState() throws Exception {
        Path file = tempDir.resolve("registration.json");
        WeComChatDataPublicKeyRegistrationStore store = WeComChatDataPublicKeyRegistrationStore.forTests(
                config(file), Clock.fixed(NOW, ZoneOffset.UTC));
        String digest = "a".repeat(64);

        assertFalse(store.isRegistered("ww-corp", 1, digest));
        store.markRegistered("ww-corp", 1, digest);

        assertTrue(store.isRegistered("ww-corp", 1, digest));
        assertFalse(store.isRegistered("ww-other", 1, digest));
        assertFalse(store.isRegistered("ww-corp", 2, digest));
        assertFalse(store.isRegistered("ww-corp", 1, "b".repeat(64)));
        JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(Set.of("authCorpId", "publicKeyVersion", "publicKeySha256", "registeredAt"),
                json.keySet());
        assertEquals("ww-corp", json.get("authCorpId").getAsString());
        assertEquals(1, json.get("publicKeyVersion").getAsInt());
        assertEquals(digest, json.get("publicKeySha256").getAsString());
        assertEquals(NOW.toString(), json.get("registeredAt").getAsString());
        assertFalse(Files.readString(file).contains("token"));
        assertFalse(Files.readString(file).contains("permanent"));
        assertFalse(Files.readString(file).contains("PRIVATE KEY"));
    }

    @Test
    void rejectsOversizedOrMalformedStateWithoutTreatingItAsRegistered() throws Exception {
        Path file = tempDir.resolve("registration.json");
        WeComChatDataPublicKeyRegistrationStore store = WeComChatDataPublicKeyRegistrationStore.forTests(
                config(file), Clock.fixed(NOW, ZoneOffset.UTC));
        Files.writeString(file, "x".repeat(4097), StandardCharsets.UTF_8);

        WeComChatDataException oversized = assertThrows(WeComChatDataException.class,
                () -> store.isRegistered("ww-corp", 1, "a".repeat(64)));
        assertEquals("WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_STATE_FAILED", oversized.code());

        Files.writeString(file, "{\"authCorpId\":\"ww-corp\"}", StandardCharsets.UTF_8);
        assertThrows(WeComChatDataException.class,
                () -> store.isRegistered("ww-corp", 1, "a".repeat(64)));
    }

    private Config config(Path file) {
        return new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FILE", file.toString()));
    }
}
