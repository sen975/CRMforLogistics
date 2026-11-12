package com.crmforlogistics.messagecenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EmailAttachmentProjectionTest {
    @Test
    void projectsBoundedPublicAttachmentMetadataInSourceOrder() throws Exception {
        Path dir = Files.createTempDirectory("email-attachment-projection");
        Path emailDir = dir.resolve("email");
        Files.createDirectories(emailDir);
        Files.writeString(emailDir.resolve("inbox.jsonl"), emailRow("stored", ""), StandardCharsets.UTF_8);

        UnifiedMessageStore store = new UnifiedMessageStore(config(dir, emailDir));
        UnifiedMessage message = store.thread("email:buyer@example.com").get(0);

        assertEquals(List.of("quote.pdf", "photo.jpg", "too-large.zip"),
                message.attachments.stream().map(EmailAttachment::fileName).toList());
        assertEquals("stored", message.attachments.get(0).state());
        assertEquals("rejected", message.attachments.get(2).state());
        assertEquals("EMAIL_ATTACHMENT_SIZE_LIMIT", message.attachments.get(2).errorCode());
        assertEquals(null, message.attachments.get(0).relativePath());
        assertFalse(message.raw.contains("relativePath"));

        UnifiedMessage found = store.findMessage("email:mail-attachment-1");
        assertNotNull(found);
        assertEquals(3, found.attachments.size());
    }

    @Test
    void missingOrDamagedAttachmentArraysDoNotDropEmailRows() throws Exception {
        Path dir = Files.createTempDirectory("email-attachment-damaged");
        Path emailDir = dir.resolve("email");
        Files.createDirectories(emailDir);
        Files.writeString(emailDir.resolve("inbox.jsonl"), ""
                + emailRowWithoutAttachments("mail-no-attachments", "2026-08-05T01:00:00Z") + "\n"
                + emailRowWithRawAttachments("mail-damaged-attachments", "2026-08-05T01:01:00Z", "\"invalid\"") + "\n",
                StandardCharsets.UTF_8);

        List<UnifiedMessage> messages = new UnifiedMessageStore(config(dir, emailDir))
                .thread("email:buyer@example.com");

        assertEquals(2, messages.size());
        assertEquals(List.of(), messages.get(0).attachments);
        assertEquals(List.of(), messages.get(1).attachments);
    }

    @Test
    void attachmentStateChangesThreadRevision() throws Exception {
        Path dir = Files.createTempDirectory("email-attachment-revision");
        Path emailDir = dir.resolve("email");
        Files.createDirectories(emailDir);
        Path inbox = emailDir.resolve("inbox.jsonl");
        Files.writeString(inbox, emailRow("stored", ""), StandardCharsets.UTF_8);
        UnifiedMessageStore store = new UnifiedMessageStore(config(dir, emailDir));
        String firstRevision = store.threadPage("email:buyer@example.com", "", 10).threadRevision;

        Files.writeString(inbox, emailRow("rejected", "EMAIL_ATTACHMENT_READ_FAILED"), StandardCharsets.UTF_8);
        String secondRevision = store.threadPage("email:buyer@example.com", "", 10).threadRevision;

        assertNotEquals(firstRevision, secondRevision);
    }

    private static String emailRow(String firstState, String firstErrorCode) {
        String firstPath = "stored".equals(firstState) ? "\"relativePath\":\"attachments/mail-attachment-1/a\"," : "";
        String firstError = firstErrorCode.isBlank() ? "null" : "\"" + firstErrorCode + "\"";
        String attachments = "["
                + "{\"id\":\"a1\",\"fileName\":\"quote.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":3,"
                + firstPath + "\"state\":\"" + firstState + "\",\"errorCode\":" + firstError + "},"
                + "{\"id\":\"a2\",\"fileName\":\"photo.jpg\",\"mimeType\":\"image/jpeg\",\"sizeBytes\":4,"
                + "\"relativePath\":\"attachments/mail-attachment-1/b\",\"state\":\"stored\",\"errorCode\":null},"
                + "{\"id\":\"a3\",\"fileName\":\"too-large.zip\",\"mimeType\":\"application/zip\",\"sizeBytes\":0,"
                + "\"state\":\"rejected\",\"errorCode\":\"EMAIL_ATTACHMENT_SIZE_LIMIT\"}]";
        return emailRowWithRawAttachments("mail-attachment-1", "2026-08-05T01:00:00Z", attachments) + "\n";
    }

    private static String emailRowWithoutAttachments(String id, String sentDate) {
        return "{\"id\":\"" + id + "\",\"direction\":\"in\",\"contactEmail\":\"buyer@example.com\","
                + "\"from\":\"buyer@example.com\",\"to\":\"seller@example.com\",\"subject\":\"Quote\","
                + "\"sentDate\":\"" + sentDate + "\",\"bodyText\":\"Body\"}";
    }

    private static String emailRowWithRawAttachments(String id, String sentDate, String attachments) {
        String base = emailRowWithoutAttachments(id, sentDate);
        return base.substring(0, base.length() - 1) + ",\"attachments\":" + attachments + "}";
    }

    private static Config config(Path dir, Path emailDir) {
        Map<String, String> values = new HashMap<>();
        values.put("DATA_DIR", dir.toString());
        values.put("EMAIL_DATA_DIR", emailDir.toString());
        values.put("CHATAPP_DATA_FILE", dir.resolve("chatapp.jsonl").toString());
        values.put("CHATAPP_TEMPLATE_FILE", dir.resolve("templates.json").toString());
        values.put("CONTACT_GROUP_FILE", dir.resolve("contact-groups.jsonl").toString());
        values.put("EMAIL_CONTACT_GROUP_FILE", emailDir.resolve("contact-groups.jsonl").toString());
        return new Config(values);
    }
}
