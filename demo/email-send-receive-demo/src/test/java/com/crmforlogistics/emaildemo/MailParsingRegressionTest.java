package com.crmforlogistics.emaildemo;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class MailParsingRegressionTest {
    public static void main(String[] args) throws Exception {
        decodesHtmlEntitiesAfterStrippingTags();
        decodesMimeEncodedAddressPersonalName();
        groupsIncomingAndOutgoingMailByContact();
        storesSentFolderMailAsOutgoingByRecipient();
        sortsThreadByMailSentDateInsteadOfImportTime();
        mergesContactsWithoutRewritingMailRecords();
        computesPollingWindowAfterLastKnownCount();
        skipsDuplicateIncomingMailByMessageId();
    }

    private static void decodesHtmlEntitiesAfterStrippingTags() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setContent("<p>Dear&nbsp;Mr.&nbsp;Huang,&nbsp;&lt;test&gt;</p>", "text/html; charset=UTF-8");
        message.saveChanges();

        String body = MailPrinter.bodyText(message);
        assertEquals("Dear Mr. Huang, <test>", body);
    }

    private static void decodesMimeEncodedAddressPersonalName() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("3332099198@qq.com", "Test User", "UTF-8"));
        message.setText("hello", "UTF-8");
        message.saveChanges();

        InboxStore store = new InboxStore(testConfig(Files.createTempDirectory("mail-demo-test")));
        store.append(message);

        String decoded = store.list().get(0).from();
        assertEquals("Test User <3332099198@qq.com>", decoded);
    }

    private static void groupsIncomingAndOutgoingMailByContact() throws Exception {
        InboxStore store = new InboxStore(testConfig(Files.createTempDirectory("mail-demo-thread-test")));

        MimeMessage incoming = new MimeMessage(Session.getInstance(new Properties()));
        incoming.setFrom(new InternetAddress("buyer@example.com", "Buyer", "UTF-8"));
        incoming.setSubject("Need quote", "UTF-8");
        incoming.setText("Please send price.", "UTF-8");
        incoming.saveChanges();
        store.append(incoming);
        store.appendOutgoing("Buyer <buyer@example.com>", "Quote sent", "Here is the quote.", "<demo-message-id>");

        assertEquals("buyer@example.com", store.contacts().get(0).email());
        assertEquals("Quote sent", store.contacts().get(0).lastSubject());
        assertEquals("2", Integer.toString(store.listByContact("buyer@example.com").size()));
        assertEquals("in", store.listByContact("buyer@example.com").get(0).direction());
        assertEquals("out", store.listByContact("buyer@example.com").get(1).direction());
    }


    private static void storesSentFolderMailAsOutgoingByRecipient() throws Exception {
        InboxStore store = new InboxStore(testConfig(Files.createTempDirectory("mail-demo-sent-folder-test")));

        MimeMessage sent = new MimeMessage(Session.getInstance(new Properties()));
        sent.setFrom(new InternetAddress("sender@example.com", "Demo Sender", "UTF-8"));
        sent.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress("buyer@example.com", "Buyer", "UTF-8"));
        sent.setSubject("Sent from another client", "UTF-8");
        sent.setText("This message already exists in the mailbox sent folder.", "UTF-8");
        sent.setHeader("Message-ID", "<sent-message-id@example.com>");
        sent.saveChanges();

        store.appendSent(sent);

        InboxRecord record = store.listByContact("buyer@example.com").get(0);
        assertEquals("out", record.direction());
        assertEquals("buyer@example.com", record.contactEmail());
        assertEquals("Demo Sender <sender@example.com>", record.from());
        assertEquals("Buyer <buyer@example.com>", record.to());
        assertEquals("Sent from another client", record.subject());
    }

    private static void sortsThreadByMailSentDateInsteadOfImportTime() throws Exception {
        InboxStore store = new InboxStore(testConfig(Files.createTempDirectory("mail-demo-time-sort-test")));

        MimeMessage newerIncoming = new MimeMessage(Session.getInstance(new Properties()));
        newerIncoming.setFrom(new InternetAddress("buyer@example.com", "Buyer", "UTF-8"));
        newerIncoming.setSubject("Newer incoming", "UTF-8");
        newerIncoming.setText("newer", "UTF-8");
        newerIncoming.setSentDate(new java.util.Date(1_700_100_000_000L));
        newerIncoming.setHeader("Message-ID", "<newer-incoming@example.com>");
        newerIncoming.saveChanges();

        MimeMessage olderSent = new MimeMessage(Session.getInstance(new Properties()));
        olderSent.setFrom(new InternetAddress("sender@example.com", "Demo Sender", "UTF-8"));
        olderSent.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress("buyer@example.com", "Buyer", "UTF-8"));
        olderSent.setSubject("Older sent", "UTF-8");
        olderSent.setText("older", "UTF-8");
        olderSent.setSentDate(new java.util.Date(1_700_000_000_000L));
        olderSent.setHeader("Message-ID", "<older-sent@example.com>");
        olderSent.saveChanges();

        store.append(newerIncoming);
        store.appendSent(olderSent);

        assertEquals("Older sent", store.listByContact("buyer@example.com").get(0).subject());
        assertEquals("Newer incoming", store.listByContact("buyer@example.com").get(1).subject());
    }

    private static void mergesContactsWithoutRewritingMailRecords() throws Exception {
        InboxStore store = new InboxStore(testConfig(Files.createTempDirectory("mail-demo-contact-merge-test")));

        MimeMessage olderAlias = new MimeMessage(Session.getInstance(new Properties()));
        olderAlias.setFrom(new InternetAddress("alias@example.com", "Buyer Alias", "UTF-8"));
        olderAlias.setSubject("Alias first", "UTF-8");
        olderAlias.setText("first", "UTF-8");
        olderAlias.setSentDate(new java.util.Date(1_700_000_000_000L));
        olderAlias.setHeader("Message-ID", "<alias-first@example.com>");
        olderAlias.saveChanges();

        MimeMessage newerPrimary = new MimeMessage(Session.getInstance(new Properties()));
        newerPrimary.setFrom(new InternetAddress("buyer@example.com", "Buyer", "UTF-8"));
        newerPrimary.setSubject("Primary second", "UTF-8");
        newerPrimary.setText("second", "UTF-8");
        newerPrimary.setSentDate(new java.util.Date(1_700_100_000_000L));
        newerPrimary.setHeader("Message-ID", "<primary-second@example.com>");
        newerPrimary.saveChanges();

        store.append(newerPrimary);
        store.append(olderAlias);

        assertEquals("2", Integer.toString(store.contacts().size()));

        store.mergeContacts("buyer@example.com", "alias@example.com");

        assertEquals("1", Integer.toString(store.contacts().size()));
        assertEquals("buyer@example.com", store.contacts().get(0).email());
        assertEquals("2", Integer.toString(store.contacts().get(0).messageCount()));
        assertEquals("alias@example.com", store.contactGroup("buyer@example.com").get(1));
        assertEquals("Alias first", store.listByContact("buyer@example.com").get(0).subject());
        assertEquals("Primary second", store.listByContact("buyer@example.com").get(1).subject());

        store.splitContact("buyer@example.com", "alias@example.com");

        assertEquals("2", Integer.toString(store.contacts().size()));
        assertEquals("1", Integer.toString(store.listByContact("buyer@example.com").size()));
        assertEquals("1", Integer.toString(store.listByContact("alias@example.com").size()));
    }

    private static void computesPollingWindowAfterLastKnownCount() {
        assertEquals("11", Integer.toString(ImapMailbox.firstNewMessageNumber(10, 12)));
        assertEquals("-1", Integer.toString(ImapMailbox.firstNewMessageNumber(12, 12)));
    }

    private static void skipsDuplicateIncomingMailByMessageId() throws Exception {
        InboxStore store = new InboxStore(testConfig(Files.createTempDirectory("mail-demo-dedupe-test")));

        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("buyer@example.com", "Buyer", "UTF-8"));
        message.setSubject("Repeated import", "UTF-8");
        message.setText("Only one record should be stored.", "UTF-8");
        message.setHeader("Message-ID", "<same-message-id@example.com>");
        message.saveChanges();

        store.append(message);
        store.append(message);

        assertEquals("1", Integer.toString(store.listByContact("buyer@example.com").size()));
    }

    private static MailConfig testConfig(Path dataDir) {
        return new MailConfig(
                "smtp.example.com",
                465,
                true,
                false,
                "sender@example.com",
                "secret",
                "sender@example.com",
                "Demo",
                "sender@example.com",
                "imap.example.com",
                993,
                true,
                "sender@example.com",
                "secret",
                "INBOX",
                "Sent",
                10,
                30,
                dataDir,
                8088
        );
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]");
        }
    }
}
