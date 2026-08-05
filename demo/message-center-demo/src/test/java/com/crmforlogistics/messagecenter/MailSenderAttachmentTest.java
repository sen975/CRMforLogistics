package com.crmforlogistics.messagecenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MailSenderAttachmentTest {
    @Test
    void buildsMixedMimeMessageWithOrderedUtf8Attachments() throws Exception {
        Path dir = Files.createTempDirectory("mail-sender-attachments");
        Path emailDir = dir.resolve("email");
        Files.createDirectories(emailDir);
        Config config = config(dir, emailDir);
        EmailAttachmentStore attachmentStore = new EmailAttachmentStore(config);
        StagedAttachment first = attachmentStore.stage(new ByteArrayInputStream("报价".getBytes(StandardCharsets.UTF_8)),
                "报价.pdf", "application/pdf", new AttachmentBudget(16, 20_971_520L, 10_737_418_240L));
        StagedAttachment second = attachmentStore.stage(new ByteArrayInputStream(new byte[]{1, 2, 3}),
                "photo.jpg", "image/jpeg", new AttachmentBudget(16, 20_971_520L, 10_737_418_240L));
        AtomicReference<MimeMessage> captured = new AtomicReference<>();
        MailSender sender = new MailSender(config, (message, recipients) -> {
            var bytes = new java.io.ByteArrayOutputStream();
            try {
                message.writeTo(bytes);
                captured.set(new MimeMessage(Session.getInstance(new Properties()),
                        new ByteArrayInputStream(bytes.toByteArray())));
            } catch (java.io.IOException exception) {
                throw new jakarta.mail.MessagingException("capture failed", exception);
            }
        }, attachmentStore);

        sender.send(new EmailSendCommand("buyer@example.com", "Quote", "See attached", "local-mail-1",
                List.of(first, second)));

        MimeMessage message = captured.get();
        assertNotNull(message);
        assertEquals("multipart/mixed", message.getContentType().split(";", 2)[0].toLowerCase());
        Multipart multipart = (Multipart) message.getContent();
        assertEquals(3, multipart.getCount());
        assertEquals("See attached", ((MimeBodyPart) multipart.getBodyPart(0)).getContent().toString().trim());
        assertEquals("报价.pdf", MimeUtility.decodeText(((MimeBodyPart) multipart.getBodyPart(1)).getFileName()));
        assertEquals("application/pdf", ((MimeBodyPart) multipart.getBodyPart(1)).getContentType().split(";", 2)[0]);
        assertEquals("photo.jpg", ((MimeBodyPart) multipart.getBodyPart(2)).getFileName());
        assertArrayEquals(new byte[]{1, 2, 3}, ((MimeBodyPart) multipart.getBodyPart(2)).getInputStream().readAllBytes());
    }

    @Test
    void zeroAttachmentsStillSendsPlainTextAndSmtpFailureDoesNotWriteHistory() throws Exception {
        Path dir = Files.createTempDirectory("mail-sender-no-attachments");
        Path emailDir = dir.resolve("email");
        Files.createDirectories(emailDir);
        Config config = config(dir, emailDir);
        MailSender sender = new MailSender(config, (message, recipients) -> {
            throw new jakarta.mail.MessagingException("capture failure");
        });

        assertThrows(Exception.class, () -> sender.send(new EmailSendCommand(
                "buyer@example.com", "Subject", "Body", "local-mail-2", List.of())));
        assertFalse(Files.exists(config.emailInboxFile()));
    }

    private static Config config(Path dir, Path emailDir) {
        Map<String, String> values = new HashMap<>();
        values.put("DATA_DIR", dir.toString());
        values.put("EMAIL_DATA_DIR", emailDir.toString());
        values.put("CHATAPP_DATA_FILE", dir.resolve("chatapp.jsonl").toString());
        values.put("CHATAPP_TEMPLATE_FILE", dir.resolve("templates.json").toString());
        values.put("CONTACT_GROUP_FILE", dir.resolve("groups.jsonl").toString());
        values.put("EMAIL_CONTACT_GROUP_FILE", emailDir.resolve("groups.jsonl").toString());
        values.put("MAIL_FROM", "sender@example.com");
        values.put("SMTP_USERNAME", "sender@example.com");
        values.put("SMTP_HOST", "localhost");
        values.put("SMTP_PORT", "2525");
        values.put("SMTP_SSL", "false");
        return new Config(values);
    }
}
