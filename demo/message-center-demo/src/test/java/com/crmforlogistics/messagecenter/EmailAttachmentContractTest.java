package com.crmforlogistics.messagecenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EmailAttachmentContractTest {
    @Test
    void emailProjectionExposesOnlyDownloadableMetadata() throws Exception {
        Path root = Files.createTempDirectory("email-attachment-contract");
        Path emailDir = root.resolve("email");
        Files.createDirectories(emailDir);
        Files.writeString(emailDir.resolve("inbox.jsonl"), ""
                + "{\"id\":\"mail-contract-1\",\"direction\":\"in\","
                + "\"contactEmail\":\"buyer@example.com\",\"from\":\"buyer@example.com\","
                + "\"to\":\"seller@example.com\",\"subject\":\"Quote\","
                + "\"sentDate\":\"2026-08-05T01:00:00Z\",\"bodyText\":\"See attached\","
                + "\"attachments\":[{\"id\":\"11111111-1111-1111-1111-111111111111\","
                + "\"fileName\":\"报价.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":3,"
                + "\"relativePath\":\"attachments/mail-contract-1/secret\",\"state\":\"stored\","
                + "\"errorCode\":null}]}\n", StandardCharsets.UTF_8);

        UnifiedMessage message = new UnifiedMessageStore(config(root, emailDir))
                .thread("email:buyer@example.com").get(0);

        assertEquals(1, message.attachments.size());
        EmailAttachment attachment = message.attachments.get(0);
        assertEquals("11111111-1111-1111-1111-111111111111", attachment.id());
        assertEquals("报价.pdf", attachment.fileName());
        assertEquals("application/pdf", attachment.mimeType());
        assertEquals(3L, attachment.sizeBytes());
        assertEquals("stored", attachment.state());
        assertNull(attachment.relativePath());
        assertFalse(message.raw.contains("relativePath"));
    }

    private static Config config(Path root, Path emailDir) {
        Map<String, String> values = new HashMap<>();
        values.put("DATA_DIR", root.toString());
        values.put("EMAIL_DATA_DIR", emailDir.toString());
        values.put("CHATAPP_DATA_FILE", root.resolve("chatapp.jsonl").toString());
        values.put("CHATAPP_TEMPLATE_FILE", root.resolve("templates.json").toString());
        values.put("CONTACT_GROUP_FILE", root.resolve("groups.jsonl").toString());
        values.put("EMAIL_CONTACT_GROUP_FILE", emailDir.resolve("groups.jsonl").toString());
        return new Config(values);
    }
}
