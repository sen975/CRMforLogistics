package com.crmforlogistics.messagecenter.channel.email;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailMimeParserTest {
    @Test
    void decodesHtmlEntitiesWhenProjectingHtmlToPlainText() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setContent("<p>品名：LED&nbsp;灯带&nbsp;&amp;&nbsp;数量：800</p>", "text/html; charset=UTF-8");
        message.saveChanges();

        var parsed = new EmailMimeParser().parse(message);

        assertEquals("品名：LED 灯带 & 数量：800", parsed.bodyText());
    }

    @Test
    void selectsPlainBodyAndReturnsOnlyOrdinaryAttachments() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        var mixed = new MimeMultipart("mixed");
        var alternative = new MimeBodyPart();
        var alt = new MimeMultipart("alternative");
        var plain = new MimeBodyPart(); plain.setText("plain body", "UTF-8");
        var html = new MimeBodyPart(); html.setContent("<p>html body</p>", "text/html; charset=UTF-8");
        alt.addBodyPart(plain); alt.addBodyPart(html); alternative.setContent(alt);
        mixed.addBodyPart(alternative);
        var file = new MimeBodyPart(); file.setFileName("a.pdf"); file.setContent("pdf", "application/pdf"); file.setDisposition(Message.ATTACHMENT); mixed.addBodyPart(file);
        var inline = new MimeBodyPart(); inline.setFileName("logo.png"); inline.setContent("x", "image/png"); inline.setDisposition(Message.INLINE); inline.setHeader("Content-ID", "<logo>"); mixed.addBodyPart(inline);
        message.setContent(mixed); message.saveChanges();
        var parsed = new EmailMimeParser().parse(message);
        assertEquals("plain body", parsed.bodyText());
        assertEquals(1, parsed.attachments().size(), parsed.errorCodes().toString());
        assertEquals("a.pdf", parsed.attachments().get(0).fileName());
    }
}
