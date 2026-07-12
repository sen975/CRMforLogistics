package com.crmforlogistics.emaildemo;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;

import java.util.Properties;

public class ImapMailbox {
    private final MailConfig config;

    public ImapMailbox(MailConfig config) {
        this.config = config;
    }

    public void verify() throws MessagingException {
        try (Store ignored = connectStore()) {
            // connectStore verifies the IMAP login.
        }
    }


    public void printFolders() throws Exception {
        try (Store store = connectStore()) {
            System.out.println("[INFO] IMAP folders:");
            for (Folder folder : store.getDefaultFolder().list("*")) {
                System.out.println("  " + folder.getFullName());
            }
        }
    }
    public void receiveLatest(int limit, InboxStore inboxStore) throws Exception {
        try (Store store = connectStore()) {
            receiveLatestFromFolder(store, config.inboxFolder(), "in", limit, inboxStore);
            receiveLatestFromFolder(store, config.sentFolder(), "out", limit, inboxStore);
        }
    }

    public void watch(InboxStore inboxStore) throws Exception {
        int lastKnownInboxCount = currentMessageCount(config.inboxFolder());
        int lastKnownSentCount = currentMessageCount(config.sentFolder());
        System.out.println("[INFO] Watching mail folders by polling every " + config.watchPollSeconds() + " seconds.");
        System.out.println("[INFO] Current inbox message count: " + lastKnownInboxCount);
        System.out.println("[INFO] Current sent message count: " + lastKnownSentCount);

        while (true) {
            Thread.sleep(config.watchPollSeconds() * 1000L);
            try {
                lastKnownInboxCount = pollOnce(config.inboxFolder(), "in", inboxStore, lastKnownInboxCount);
                lastKnownSentCount = pollOnce(config.sentFolder(), "out", inboxStore, lastKnownSentCount);
            } catch (Exception e) {
                System.err.println("[WARN] IMAP polling failed. Retrying after "
                        + config.watchPollSeconds() + " seconds: " + e.getMessage());
            }
        }
    }

    int pollOnce(InboxStore inboxStore, int lastKnownCount) throws Exception {
        return pollOnce(config.inboxFolder(), "in", inboxStore, lastKnownCount);
    }

    int pollOnce(String folderName, String direction, InboxStore inboxStore, int lastKnownCount) throws Exception {
        if (isBlank(folderName) || lastKnownCount < 0) {
            return lastKnownCount;
        }
        try (Store store = connectStore()) {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                System.err.println("[WARN] IMAP folder does not exist: " + folderName);
                return -1;
            }
            folder.open(Folder.READ_ONLY);
            try {
                int current = folder.getMessageCount();
                if (current < lastKnownCount) {
                    System.out.println("[INFO] Message count decreased in " + folderName + " from " + lastKnownCount + " to " + current + ". Resetting watch cursor.");
                    return current;
                }
                int firstNew = firstNewMessageNumber(lastKnownCount, current);
                if (firstNew < 0) {
                    System.out.println("[INFO] No new " + directionName(direction) + " email. Folder " + folderName + " message count: " + current);
                    return current;
                }
                Message[] messages = folder.getMessages(firstNew, current);
                System.out.println("[INFO] New " + directionName(direction) + " email count: " + messages.length);
                for (Message message : messages) {
                    notifyNewMail(message, direction, inboxStore);
                }
                return current;
            } finally {
                folder.close(false);
            }
        }
    }

    static int firstNewMessageNumber(int lastKnownCount, int currentCount) {
        if (currentCount <= lastKnownCount) {
            return -1;
        }
        return Math.max(1, lastKnownCount + 1);
    }

    private void receiveLatestFromFolder(Store store, String folderName, String direction, int limit, InboxStore inboxStore) throws Exception {
        if (isBlank(folderName)) {
            return;
        }
        Folder folder = store.getFolder(folderName);
        if (!folder.exists()) {
            System.err.println("[WARN] IMAP folder does not exist: " + folderName);
            return;
        }
        folder.open(Folder.READ_ONLY);
        try {
            int count = folder.getMessageCount();
            if (count == 0) {
                System.out.println("[INFO] Folder is empty: " + folderName);
                return;
            }
            int start = Math.max(1, count - limit + 1);
            Message[] messages = folder.getMessages(start, count);
            System.out.println("[INFO] Receiving latest " + messages.length + " " + directionName(direction) + " emails from " + folderName + ".");
            for (Message message : messages) {
                MailPrinter.print(message, "[MAIL]");
                appendByDirection(inboxStore, message, direction);
            }
        } finally {
            folder.close(false);
        }
    }

    private int currentMessageCount(String folderName) throws Exception {
        if (isBlank(folderName)) {
            return -1;
        }
        try (Store store = connectStore()) {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                System.err.println("[WARN] IMAP folder does not exist: " + folderName);
                return -1;
            }
            folder.open(Folder.READ_ONLY);
            try {
                return folder.getMessageCount();
            } finally {
                folder.close(false);
            }
        }
    }

    private void notifyNewMail(Message message, String direction, InboxStore inboxStore) throws Exception {
        System.out.println();
        System.out.println("[NEW MAIL] Received new " + directionName(direction) + " email.");
        MailPrinter.print(message, "[NEW MAIL]");
        appendByDirection(inboxStore, message, direction);
    }

    private void appendByDirection(InboxStore inboxStore, Message message, String direction) throws Exception {
        if ("out".equals(direction)) {
            inboxStore.appendSent(message);
        } else {
            inboxStore.append(message);
        }
    }

    private Store connectStore() throws MessagingException {
        Session session = Session.getInstance(imapProperties());
        Store store = session.getStore("imap");
        store.connect(config.imapHost(), config.imapPort(), config.imapUsername(), config.imapPassword());
        return store;
    }

    private Properties imapProperties() {
        Properties props = new Properties();
        props.put("mail.imap.host", config.imapHost());
        props.put("mail.imap.port", Integer.toString(config.imapPort()));
        props.put("mail.imap.ssl.enable", Boolean.toString(config.imapSsl()));
        props.put("mail.imap.connectiontimeout", "15000");
        props.put("mail.imap.timeout", "30000");
        return props;
    }

    private static String directionName(String direction) {
        return "out".equals(direction) ? "sent" : "incoming";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}