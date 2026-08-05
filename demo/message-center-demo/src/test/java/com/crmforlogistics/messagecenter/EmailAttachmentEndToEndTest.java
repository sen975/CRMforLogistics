package com.crmforlogistics.messagecenter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EmailAttachmentEndToEndTest {
    @Test
    void smtpMimeRoundTripKeepsAttachmentFactsAndMessageBinding() throws Exception {
        Path root = Files.createTempDirectory("email-attachment-e2e");
        Config config = config(root, Map.of());
        EmailAttachmentStore store = new EmailAttachmentStore(config);
        byte[] quote = "中文报价单".getBytes(StandardCharsets.UTF_8);
        byte[] photo = new byte[]{0, 1, 2, 3, 4, (byte) 255};
        AttachmentBudget budget = new AttachmentBudget(16, 20_971_520L, 10_737_418_240L);
        StagedAttachment first = store.stage(new ByteArrayInputStream(quote), "报价单.pdf", "application/pdf", budget);
        StagedAttachment second = store.stage(new ByteArrayInputStream(photo), "photo.jpg", "image/jpeg", budget);
        AtomicReference<byte[]> smtpCapture = new AtomicReference<>();
        MailSender sender = new MailSender(config, (message, recipients) -> {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                message.writeTo(output);
                smtpCapture.set(output.toByteArray());
            } catch (Exception exception) {
                throw new jakarta.mail.MessagingException("capture failed", exception);
            }
        }, store);

        UnifiedMessage sent = sender.send(new EmailSendCommand(
                "buyer@example.com", "Quote", "See attached", "outgoing-message-1", List.of(first, second)));
        MimeMessage received = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(smtpCapture.get()));
        assertTrue(new EmailInboxWriter(config).append(received, "in"));

        List<JsonObject> rows = Files.readAllLines(config.emailInboxFile(), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> JsonParser.parseString(line).getAsJsonObject())
                .toList();
        assertEquals(2, rows.size());
        JsonObject outbound = rows.stream().filter(row -> "out".equals(row.get("direction").getAsString())).findFirst().orElseThrow();
        JsonObject inbound = rows.stream().filter(row -> "in".equals(row.get("direction").getAsString())).findFirst().orElseThrow();
        assertEquals("outgoing-message-1", sent.sourceId);
        assertEquals(sent.sourceId, outbound.get("id").getAsString());

        assertAttachment(store, outbound, 0, "报价单.pdf", "application/pdf", quote);
        assertAttachment(store, outbound, 1, "photo.jpg", "image/jpeg", photo);
        assertAttachment(store, inbound, 0, "报价单.pdf", "application/pdf", quote);
        assertAttachment(store, inbound, 1, "photo.jpg", "image/jpeg", photo);

        String inboundId = inbound.get("id").getAsString();
        String inboundAttachmentId = inbound.getAsJsonArray("attachments").get(0).getAsJsonObject().get("id").getAsString();
        assertThrows(EmailAttachmentStoreException.class,
                () -> store.open(outbound.get("id").getAsString(), inboundAttachmentId));
        assertFalse(inboundId.equals(outbound.get("id").getAsString()));
    }

    @Test
    void aggregateLimitFailsBeforeSmtpTransport() throws Exception {
        Path root = Files.createTempDirectory("email-attachment-e2e-limit");
        Config config = config(root, Map.of("EMAIL_ATTACHMENT_MAX_TOTAL_BYTES", "1048576"));
        EmailAttachmentStore store = new EmailAttachmentStore(config);
        AttachmentBudget budget = new AttachmentBudget(16, 1_048_576L, 10_737_418_240L);
        StagedAttachment first = store.stage(new ByteArrayInputStream(new byte[600 * 1024]), "a.bin", "application/octet-stream", budget);
        StagedAttachment second = store.stage(new ByteArrayInputStream(new byte[600 * 1024]), "b.bin", "application/octet-stream", budget);
        AtomicBoolean smtpCalled = new AtomicBoolean();
        MailSender sender = new MailSender(config, (message, recipients) -> smtpCalled.set(true), store);

        Exception error = assertThrows(Exception.class, () -> sender.send(new EmailSendCommand(
                "buyer@example.com", "oversize", "body", "oversize-message", List.of(first, second))));

        assertEquals("EMAIL_ATTACHMENT_SIZE_LIMIT", error.getMessage());
        assertFalse(smtpCalled.get());
        assertFalse(Files.exists(first.temporaryPath()));
        assertFalse(Files.exists(second.temporaryPath()));
    }

    private static void assertAttachment(EmailAttachmentStore store, JsonObject row, int index,
                                         String fileName, String mimeType, byte[] expected) throws Exception {
        JsonArray attachments = row.getAsJsonArray("attachments");
        assertEquals(2, attachments.size());
        JsonObject attachment = attachments.get(index).getAsJsonObject();
        assertEquals(fileName, attachment.get("fileName").getAsString());
        assertTrue(attachment.get("mimeType").getAsString().toLowerCase().startsWith(mimeType));
        assertEquals(expected.length, attachment.get("sizeBytes").getAsLong());
        String messageId = row.get("id").getAsString();
        String attachmentId = attachment.get("id").getAsString();
        byte[] actual;
        try (var input = store.open(messageId, attachmentId)) {
            actual = input.readAllBytes();
        }
        assertArrayEquals(expected, actual);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected),
                MessageDigest.getInstance("SHA-256").digest(actual));
    }

    private static Config config(Path root, Map<String, String> overrides) {
        Path email = root.resolve("email");
        Map<String, String> values = new HashMap<>();
        values.put("DATA_DIR", root.resolve("data").toString());
        values.put("EMAIL_DATA_DIR", email.toString());
        values.put("EMAIL_CONTACT_GROUP_FILE", email.resolve("groups.jsonl").toString());
        values.put("CONTACT_GROUP_FILE", root.resolve("groups.jsonl").toString());
        values.put("CHATAPP_DATA_FILE", root.resolve("chatapp.jsonl").toString());
        values.put("CHATAPP_TEMPLATE_FILE", root.resolve("templates.json").toString());
        values.put("MAIL_FROM", "seller@example.com");
        values.put("SMTP_USERNAME", "seller@example.com");
        values.put("SMTP_HOST", "localhost");
        values.put("SMTP_PORT", "2525");
        values.put("SMTP_SSL", "false");
        values.putAll(overrides);
        return new Config(values);
    }
}
