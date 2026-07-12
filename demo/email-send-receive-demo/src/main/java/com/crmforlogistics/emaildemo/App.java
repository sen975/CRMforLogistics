package com.crmforlogistics.emaildemo;

import jakarta.mail.MessagingException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class App {
    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "help" : args[0].trim().toLowerCase();
        MailConfig config = MailConfig.load();
        if ("verify".equals(command)) {
            verify(config);
        } else if ("send".equals(command)) {
            send(config);
        } else if ("folders".equals(command)) {
            folders(config);
        } else if ("receive".equals(command)) {
            receive(config);
        } else if ("watch".equals(command)) {
            watch(config);
        } else if ("web".equals(command)) {
            web(config);
        } else if ("watch-web".equals(command)) {
            watchWeb(config);
        } else {
            printHelp();
        }
    }

    private static void verify(MailConfig config) throws MessagingException {
        new SmtpMailer(config).verify();
        System.out.println("[OK] SMTP login verified");
        new ImapMailbox(config).verify();
        System.out.println("[OK] IMAP login verified");
    }

    private static void send(MailConfig config) throws MessagingException {
        String to = firstNonBlank(System.getProperty("mail.to"), config.mailTo());
        String subject = firstNonBlank(
                System.getProperty("mail.subject"),
                "CRM logistics mail test - " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );
        String body = firstNonBlank(
                System.getProperty("mail.body"),
                "This is a test email from the Java email-send-receive-demo."
        );
        String messageId = new SmtpMailer(config).send(to, subject, body);
        System.out.println("[OK] Email sent");
        System.out.println("To: " + to);
        System.out.println("Subject: " + subject);
        System.out.println("Message-ID: " + messageId);
    }

    private static void folders(MailConfig config) throws Exception {
        new ImapMailbox(config).printFolders();
    }

    private static void receive(MailConfig config) throws Exception {
        new ImapMailbox(config).receiveLatest(config.receiveLimit(), new InboxStore(config));
    }

    private static void watch(MailConfig config) throws Exception {
        System.out.println("[INFO] Watching new emails. Press Ctrl+C to stop.");
        new ImapMailbox(config).watch(new InboxStore(config));
    }

    private static void web(MailConfig config) throws Exception {
        new InboxWebServer(config, new InboxStore(config)).startAndBlock();
    }

    private static void watchWeb(MailConfig config) throws Exception {
        new InboxWebServer(config, new InboxStore(config)).start();
        System.out.println("[INFO] Web inbox started. Watching new emails.");
        new ImapMailbox(config).watch(new InboxStore(config));
    }

    private static void printHelp() {
        System.out.println("Usage:");
        System.out.println("  mvn -q compile exec:java \"-Dexec.args=verify\"");
        System.out.println("  mvn -q compile exec:java \"-Dexec.args=send\"");
        System.out.println("  mvn -q compile exec:java \"-Dexec.args=folders\"");
        System.out.println("  mvn -q compile exec:java \"-Dexec.args=receive\"");
        System.out.println("  mvn -q compile exec:java \"-Dexec.args=watch\"");
        System.out.println("  mvn -q compile exec:java \"-Dexec.args=web\"");
        System.out.println("  mvn -q compile exec:java \"-Dexec.args=watch-web\"");
    }

    private static String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }
}
