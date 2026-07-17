package com.crmforlogistics.messagecenter;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;

import java.security.Security;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

public class EmailSyncService {
    private static final String PROVIDER_139 = "139";
    private static final String TLS_139_CIPHER_SUITE = "TLS_RSA_WITH_AES_256_GCM_SHA384";
    private static final String TLS_DISABLED_ALGORITHMS = "jdk.tls.disabledAlgorithms";
    private static final String DISABLED_TLS_RSA_SUITES = "TLS_RSA_*";

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
        try (Store store = connectStore()) {
            receiveLatestFromFolder(store, config.value("INBOX_FOLDER", "INBOX"), "in", receiveLimit(), result);
            receiveLatestFromFolder(store, config.value("SENT_FOLDER", "Sent"), "out", receiveLimit(), result);
        }
        result.message = "received " + result.saved + " new email messages";
        return result;
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
        applyJavaSecurityProfile();
        Session session = Session.getInstance(imapProperties());
        Store store = session.getStore("imap");
        store.connect(config.value("IMAP_HOST", ""), imapPort(),
                config.value("IMAP_USERNAME", ""), config.value("IMAP_PASSWORD", ""));
        return store;
    }

    Properties imapProperties() {
        Properties props = new Properties();
        props.put("mail.imap.host", config.value("IMAP_HOST", ""));
        props.put("mail.imap.port", Integer.toString(imapPort()));
        props.put("mail.imap.ssl.enable", Boolean.toString(bool("IMAP_SSL", true)));
        props.put("mail.imap.connectiontimeout", "15000");
        props.put("mail.imap.timeout", "30000");
        props.put("mail.imap.ssl.protocols", "TLSv1.2");
        String cipherSuite = javaMailCipherSuite();
        if (!cipherSuite.isBlank()) {
            props.put("mail.imap.ssl.ciphersuites", cipherSuite);
        }
        return props;
    }

    private void applyJavaSecurityProfile() {
        if (!PROVIDER_139.equalsIgnoreCase(effectiveMailProvider())) {
            return;
        }
        String disabledAlgorithms = Security.getProperty(TLS_DISABLED_ALGORITHMS);
        String adjustedAlgorithms = withoutDisabledTlsRsa(disabledAlgorithms);
        if (!adjustedAlgorithms.equals(disabledAlgorithms)) {
            Security.setProperty(TLS_DISABLED_ALGORITHMS, adjustedAlgorithms);
        }
    }

    private String javaMailCipherSuite() {
        return PROVIDER_139.equalsIgnoreCase(effectiveMailProvider()) ? TLS_139_CIPHER_SUITE : "";
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

    private static String withoutDisabledTlsRsa(String disabledAlgorithms) {
        if (disabledAlgorithms == null || disabledAlgorithms.isBlank()) {
            return "";
        }
        return Arrays.stream(disabledAlgorithms.split(","))
                .map(String::trim)
                .filter(item -> !item.equalsIgnoreCase(DISABLED_TLS_RSA_SUITES))
                .collect(Collectors.joining(", "));
    }
}
