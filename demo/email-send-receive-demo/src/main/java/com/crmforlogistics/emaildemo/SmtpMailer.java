package com.crmforlogistics.emaildemo;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.UnsupportedEncodingException;
import java.util.Properties;

public class SmtpMailer {
    private final MailConfig config;

    public SmtpMailer(MailConfig config) {
        this.config = config;
    }

    public void verify() throws MessagingException {
        Session session = Session.getInstance(smtpProperties());
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(config.smtpHost(), config.smtpPort(), config.smtpUsername(), config.smtpPassword());
        }
    }

    public String send(String to, String subject, String body) throws MessagingException {
        Session session = Session.getInstance(smtpProperties());
        MimeMessage message = new MimeMessage(session);
        try {
            message.setFrom(new InternetAddress(config.mailFrom(), config.mailFromName(), "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new MessagingException("Failed to encode sender name", e);
        }
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");
        message.saveChanges();

        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(config.smtpHost(), config.smtpPort(), config.smtpUsername(), config.smtpPassword());
            transport.sendMessage(message, message.getAllRecipients());
        }
        return message.getMessageID();
    }

    private Properties smtpProperties() {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", config.smtpHost());
        props.put("mail.smtp.port", Integer.toString(config.smtpPort()));
        props.put("mail.smtp.ssl.enable", Boolean.toString(config.smtpSsl()));
        props.put("mail.smtp.starttls.enable", Boolean.toString(config.smtpStarttls()));
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "30000");
        return props;
    }
}
