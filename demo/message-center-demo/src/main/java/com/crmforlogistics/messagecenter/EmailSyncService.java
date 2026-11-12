package com.crmforlogistics.messagecenter;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;

import java.util.Properties;

public class EmailSyncService {
    private static final String PROVIDER_139 = "139";

    private final Config config;
    private final EmailInboxWriter writer;

    public EmailSyncService(Config config) {
        this(config, new EmailInboxWriter(config));
    }

    EmailSyncService(Config config, EmailInboxWriter writer) {
        this.config = config;
        this.writer = writer;
    }

    public SyncResult receiveLatest() throws Exception {
        SyncResult result = new SyncResult("email");
        if (usesOpenSslImapFallback()) {
            receiveLatestWithOpenSsl(result);
            result.message = "received " + result.saved + " new email messages";
            return result;
        }
        try (Store store = connectStore()) {
            receiveLatestFromFolder(store, config.value("INBOX_FOLDER", "INBOX"), "in", receiveLimit(), result);
            receiveLatestFromFolder(store, config.value("SENT_FOLDER", "Sent"), "out", receiveLimit(), result);
        }
        result.message = "received " + result.saved + " new email messages";
        return result;
    }

    boolean usesOpenSslImapFallback() {
        return PROVIDER_139.equalsIgnoreCase(effectiveMailProvider())
                && bool("IMAP_139_USE_OPENSSL", true);
    }

    private void receiveLatestWithOpenSsl(SyncResult result) throws Exception {
        requireConfig("IMAP_HOST");
        requireConfig("IMAP_USERNAME");
        requireConfig("IMAP_PASSWORD");
        OpenSslImapClient client = new OpenSslImapClient(config);
        receiveLatestFromOpenSslFolder(client, config.value("INBOX_FOLDER", "INBOX"), "in", receiveLimit(), result);
        receiveLatestFromOpenSslFolder(client, config.value("SENT_FOLDER", "Sent"), "out", receiveLimit(), result);
    }

    private void receiveLatestFromOpenSslFolder(OpenSslImapClient client, String folderName, String direction,
                                                int limit, SyncResult result) throws Exception {
        if (folderName == null || folderName.isBlank()) {
            return;
        }
        for (Message message : client.fetchLatest(folderName, limit)) {
            result.fetched++;
            if (writer.append(message, direction)) {
                result.saved++;
            } else {
                result.skipped++;
            }
        }
    }

    private void receiveLatestFromFolder(Store store, String folderName, String direction, int limit, SyncResult result) throws Exception {
        if (folderName == null || folderName.isBlank()) {
            return;
        }
        Folder folder = store.getFolder(folderName);
        if (!folder.exists()) {
            result.skipped++;
            return;
        }
        folder.open(Folder.READ_ONLY);
        try {
            int count = folder.getMessageCount();
            if (count == 0) {
                return;
            }
            int start = Math.max(1, count - Math.max(1, limit) + 1);
            Message[] messages = folder.getMessages(start, count);
            result.fetched += messages.length;
            for (Message message : messages) {
                if (writer.append(message, direction)) {
                    result.saved++;
                } else {
                    result.skipped++;
                }
            }
        } finally {
            folder.close(false);
        }
    }

    private Store connectStore() throws MessagingException {
        requireConfig("IMAP_HOST");
        requireConfig("IMAP_USERNAME");
        requireConfig("IMAP_PASSWORD");
        Session session = Session.getInstance(imapProperties());
        Store store = session.getStore(storeProtocol());
        store.connect(config.value("IMAP_HOST", ""), imapPort(),
                config.value("IMAP_USERNAME", ""), config.value("IMAP_PASSWORD", ""));
        return store;
    }

    Properties imapProperties() {
        Properties props = new Properties();
        putImapProperties(props, "mail.imap");
        putImapProperties(props, "mail.imaps");
        return props;
    }

    private void putImapProperties(Properties props, String prefix) {
        props.put(prefix + ".host", config.value("IMAP_HOST", ""));
        props.put(prefix + ".port", Integer.toString(imapPort()));
        props.put(prefix + ".ssl.enable", Boolean.toString(bool("IMAP_SSL", true)));
        props.put(prefix + ".connectiontimeout", "15000");
        props.put(prefix + ".timeout", "30000");
        props.put(prefix + ".ssl.protocols", "TLSv1.2");
        String cipherSuite = javaMailCipherSuite();
        if (!cipherSuite.isBlank()) {
            props.put(prefix + ".ssl.ciphersuites", cipherSuite);
            props.put(prefix + ".ssl.socketFactory",
                    BouncyCastle139ImapSocketFactory.create(config.value("IMAP_HOST", "")));
            props.put(prefix + ".ssl.checkserveridentity", "true");
        }
    }

    private String storeProtocol() {
        return bool("IMAP_SSL", true) ? "imaps" : "imap";
    }

    private String javaMailCipherSuite() {
        return PROVIDER_139.equalsIgnoreCase(effectiveMailProvider()) ? BouncyCastle139ImapSocketFactory.CIPHER_SUITE : "";
    }

    private String effectiveMailProvider() {
        String configured = config.value("MAIL_PROVIDER", "auto").trim();
        if (configured.isBlank() || "auto".equalsIgnoreCase(configured)) {
            String host = config.value("IMAP_HOST", "").trim().toLowerCase();
            return "139.com".equals(host) || host.endsWith(".139.com") ? PROVIDER_139 : "default";
        }
        return configured;
    }

    private int imapPort() {
        return Integer.parseInt(config.value("IMAP_PORT", "993"));
    }

    private int receiveLimit() {
        return Integer.parseInt(config.value("RECEIVE_LIMIT", "10"));
    }

    private boolean bool(String key, boolean fallback) {
        String value = config.value(key, Boolean.toString(fallback));
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private String requireConfig(String key) {
        String value = config.value(key, "");
        if (value.isBlank()) {
            throw new IllegalStateException("缺少配置: " + key);
        }
        return value;
    }

}
