package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComChatDataStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void projectsBothDirectionsAndSkipsNonDirectMessages() throws Exception {
        Config config = config(Map.of());
        WeComChatDataStore store = new WeComChatDataStore(config);
        WeComChatDataStore.SyncKey syncKey = new WeComChatDataStore.SyncKey(
                "installation-1", 1, "program-1", "ability-1");

        WeComChatDataStore.PublishResult result = store.publishPage(syncKey, "cursor-1", List.of(
                decrypted(message("outgoing", 1, "employee-1", 2, "external-1", 100), "secret-out"),
                decrypted(message("incoming", 2, "external-1", 1, "employee-1", 200), "secret-in"),
                decrypted(new WeComChatDataGateway.EncryptedMessage("group", new WeComChatDataGateway.Party(1, "employee-1"),
                        List.of(new WeComChatDataGateway.Party(1, "employee-2"),
                                new WeComChatDataGateway.Party(2, "external-1")), "group-1", 300, 2, "cipher", 1),
                        "secret-group")
        ));

        assertEquals(2, result.stored());
        assertEquals(1, result.skipped());
        List<String> lines = Files.readAllLines(config.wecomDataFile(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        JsonObject outgoing = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        JsonObject incoming = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertEquals("employee-1", outgoing.get("userid").getAsString());
        assertEquals("external-1", outgoing.get("external_userid").getAsString());
        assertEquals("secret-in", incoming.get("secret_key").getAsString());
        assertEquals("cursor-1", store.cursor(syncKey));
        assertFalse(Files.readString(config.wecomDataFile()).contains("secret-group"));
    }

    @Test
    void upsertsDuplicateReferencesAndKeepsNewestBoundedMessages() throws Exception {
        Config config = config(Map.of("WECOM_CHATDATA_STORE_MAX_MESSAGES", "2"));
        WeComChatDataStore store = new WeComChatDataStore(config);
        WeComChatDataStore.SyncKey syncKey = new WeComChatDataStore.SyncKey(
                "installation-1", 1, "program-1", "ability-1");

        store.publishPage(syncKey, "cursor-1", List.of(
                decrypted(message("msg-1", 1, "employee-1", 2, "external-1", 100), "secret-1"),
                decrypted(message("msg-2", 1, "employee-1", 2, "external-1", 200), "secret-2")
        ));
        store.publishPage(syncKey, "cursor-2", List.of(
                decrypted(message("msg-2", 1, "employee-1", 2, "external-1", 200), "secret-2-new"),
                decrypted(message("msg-3", 2, "external-1", 1, "employee-1", 300), "secret-3")
        ));

        String content = Files.readString(config.wecomDataFile());
        assertFalse(content.contains("msg-1"));
        assertTrue(content.contains("secret-2-new"));
        assertTrue(content.contains("msg-3"));
        assertEquals(2, content.lines().count());
        assertEquals("cursor-2", store.cursor(syncKey));
    }

    private Config config(Map<String, String> extra) {
        java.util.HashMap<String, String> values = new java.util.HashMap<>(extra);
        values.put("DATA_DIR", tempDir.toString());
        values.put("WECOM_DATA_FILE", tempDir.resolve("wecom-messages.jsonl").toString());
        values.put("WECOM_CHATDATA_CURSOR_FILE", tempDir.resolve("cursor.json").toString());
        return new Config(values);
    }

    private static WeComChatDataGateway.EncryptedMessage message(String msgid, int senderType, String senderId,
                                                                  int receiverType, String receiverId, long sendTime) {
        return new WeComChatDataGateway.EncryptedMessage(msgid,
                new WeComChatDataGateway.Party(senderType, senderId),
                List.of(new WeComChatDataGateway.Party(receiverType, receiverId)), "", sendTime, 2, "cipher", 1);
    }

    private static WeComChatDataStore.DecryptedMessage decrypted(
            WeComChatDataGateway.EncryptedMessage message, String secretKey) {
        return new WeComChatDataStore.DecryptedMessage(message, secretKey);
    }
}
