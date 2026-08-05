package com.crmforlogistics.messagecenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class EmailInboxAttachmentTest {
    @Test
    void storesOrdinaryAttachmentsAndExcludesInlineContentFromBody() throws Exception {
        Path dir = Files.createTempDirectory("email-inbox-attachments");
        Path emailDir = dir.resolve("email");
        Files.createDirectories(emailDir);
        Config config = config(dir, emailDir);
        EmailInboxWriter writer = new EmailInboxWriter(config);
        MimeMessage message = mixedMessage(2, 1024);

        assertTrue(writer.append(message, "in"));
        JsonObject row = JsonParser.parseString(Files.readString(config.emailInboxFile()).trim()).getAsJsonObject();
        assertFalse(row.get("bodyText").getAsString().contains("ordinary attachment"));
        JsonArray attachments = row.getAsJsonArray("attachments");
        assertEquals(2, attachments.size());
        assertEquals("quote.pdf", attachments.get(0).getAsJsonObject().get("fileName").getAsString());
        assertEquals("photo.jpg", attachments.get(1).getAsJsonObject().get("fileName").getAsString());
        assertTrue(attachments.get(0).getAsJsonObject().get("relativePath").getAsString().contains("attachments/"));
    }

    @Test
    void boundsReceivedAttachmentProjectionAfterCountLimit() throws Exception {
        Path dir = Files.createTempDirectory("email-inbox-count-limit");
        Path emailDir = dir.resolve("email");
        Files.createDirectories(emailDir);
        Config config = config(dir, emailDir);
        assertTrue(new EmailInboxWriter(config).append(mixedMessage(17, 1), "in"));

        JsonArray attachments = JsonParser.parseString(Files.readString(config.emailInboxFile()).trim())
                .getAsJsonObject().getAsJsonArray("attachments");
        assertEquals(17, attachments.size());
        assertEquals("rejected", attachments.get(16).getAsJsonObject().get("state").getAsString());
        assertEquals("EMAIL_ATTACHMENT_COUNT_LIMIT",
                attachments.get(16).getAsJsonObject().get("errorCode").getAsString());
    }

    @Test
    void duplicateMessageDoesNotLeaveAnOrphanAttachmentDirectory() throws Exception {
        Path dir = Files.createTempDirectory("email-inbox-duplicate-attachments");
        Path emailDir = dir.resolve("email");
        Files.createDirectories(emailDir);
        Config config = config(dir, emailDir);
        EmailInboxWriter writer = new EmailInboxWriter(config);
        MimeMessage message = mixedMessage(1, 8);
        message.setHeader("Message-ID", "<duplicate@example.com>");

        assertTrue(writer.append(message, "in"));
        assertFalse(writer.append(message, "in"));

        try (var directories = Files.list(emailDir.resolve("attachments"))) {
            assertEquals(1, directories.count());
        }
    }

    @Test
    void isolatedReadFailureDoesNotDiscardLaterValidAttachment() throws Exception {
        Path dir = Files.createTempDirectory("email-inbox-read-failure");
        Path emailDir = dir.resolve("email");
        Files.createDirectories(emailDir);
        Config config = config(dir, emailDir);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("buyer@example.com"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse("seller@example.com"));
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        body.setText("body", "UTF-8");
        mixed.addBodyPart(body);
        MimeBodyPart broken = new MimeBodyPart() {
            @Override public InputStream getInputStream() throws IOException {
                throw new IOException("broken attachment");
            }
        };
        broken.setFileName("broken.bin");
        broken.setDisposition(MimeBodyPart.ATTACHMENT);
        mixed.addBodyPart(broken);
        MimeBodyPart valid = new MimeBodyPart();
        valid.setDataHandler(new DataHandler(new ByteArrayDataSource("valid".getBytes(StandardCharsets.UTF_8), "text/plain")));
        valid.setFileName("valid.txt");
        valid.setDisposition(MimeBodyPart.ATTACHMENT);
        mixed.addBodyPart(valid);
        message.setContent(mixed);
        message.saveChanges();

        assertTrue(new EmailInboxWriter(config).append(message, "in"));

        JsonArray attachments = JsonParser.parseString(Files.readString(config.emailInboxFile()).trim())
                .getAsJsonObject().getAsJsonArray("attachments");
        assertEquals(2, attachments.size());
        assertTrue(attachments.asList().stream().anyMatch(element ->
                "valid.txt".equals(element.getAsJsonObject().get("fileName").getAsString())
                        && "stored".equals(element.getAsJsonObject().get("state").getAsString())));
        assertTrue(attachments.asList().stream().anyMatch(element ->
                element.getAsJsonObject().has("errorCode")
                        && "EMAIL_ATTACHMENT_READ_FAILED".equals(element.getAsJsonObject().get("errorCode").getAsString())));
    }

    private static MimeMessage mixedMessage(int ordinaryAttachmentCount, int bytesPerAttachment) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("buyer@example.com"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse("seller@example.com"));
        message.setSubject("Quote", "UTF-8");
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        body.setText("body text", "UTF-8");
        mixed.addBodyPart(body);
        for (int index = 0; index < ordinaryAttachmentCount; index++) {
            MimeBodyPart attachment = new MimeBodyPart();
            byte[] bytes = new byte[bytesPerAttachment];
            attachment.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes,
                    index % 2 == 0 ? "application/pdf" : "image/jpeg")));
            attachment.setFileName(index == 0 ? "quote.pdf" : index == 1 ? "photo.jpg" : "file-" + index + ".bin");
            attachment.setDisposition(MimeBodyPart.ATTACHMENT);
            mixed.addBodyPart(attachment);
        }
        MimeBodyPart inline = new MimeBodyPart();
        inline.setDataHandler(new DataHandler(new ByteArrayDataSource("ordinary attachment".getBytes(StandardCharsets.UTF_8), "text/plain")));
        inline.setFileName("inline.txt");
        inline.setDisposition(MimeBodyPart.INLINE);
        inline.setHeader("Content-ID", "<inline-1>");
        mixed.addBodyPart(inline);
        message.setContent(mixed);
        message.saveChanges();
        return message;
    }

    private static Config config(Path dir, Path emailDir) {
        Map<String, String> values = new HashMap<>();
        values.put("DATA_DIR", dir.toString());
        values.put("EMAIL_DATA_DIR", emailDir.toString());
        values.put("CHATAPP_DATA_FILE", dir.resolve("chatapp.jsonl").toString());
        values.put("CHATAPP_TEMPLATE_FILE", dir.resolve("templates.json").toString());
        values.put("CONTACT_GROUP_FILE", dir.resolve("groups.jsonl").toString());
        values.put("EMAIL_CONTACT_GROUP_FILE", emailDir.resolve("groups.jsonl").toString());
        return new Config(values);
    }
}
