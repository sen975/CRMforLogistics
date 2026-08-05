package com.crmforlogistics.messagecenter;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class EmailAttachmentStore {
    private final Path dataDir, tempRoot, attachmentRoot;

    public EmailAttachmentStore(Config config) throws IOException {
        this.dataDir = config.emailDataDir().toAbsolutePath().normalize();
        this.tempRoot = dataDir.resolve("attachment-tmp");
        this.attachmentRoot = dataDir.resolve("attachments");
        Files.createDirectories(tempRoot);
        Files.createDirectories(attachmentRoot);
    }

    public StagedAttachment stage(InputStream input, String fileName, String mimeType, AttachmentBudget budget) {
        String id = UUID.randomUUID().toString();
        Path requestDir = tempRoot.resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(requestDir);
            Path target = requestDir.resolve(id + ".bin");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (InputStream in = input; java.io.OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) >= 0) { if (n == 0) continue; size += n; if (size > budget.maxTotalBytes()) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_SIZE_LIMIT"); digest.update(buffer, 0, n); out.write(buffer, 0, n); }
            }
            if (storedAttachmentBytes() + size > budget.maxStorageBytes()) {
                throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_STORAGE_FULL");
            }
            return new StagedAttachment(id, sanitize(fileName), mimeType == null ? "application/octet-stream" : mimeType,
                    size, hex(digest.digest()), target);
        } catch (EmailAttachmentStoreException e) { cleanup(requestDir); throw e;
        } catch (IOException | NoSuchAlgorithmException e) { cleanup(requestDir); throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID", e); }
    }

    public List<EmailAttachment> publish(String messageId, List<StagedAttachment> staged) {
        if (messageId == null || messageId.isBlank() || messageId.contains("/") || messageId.contains("\\")) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
        Path target = attachmentRoot.resolve(messageId).normalize();
        if (!target.startsWith(attachmentRoot) || Files.exists(target)) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
        Path assembly = tempRoot.resolve("publish-" + UUID.randomUUID());
        try {
            Files.createDirectories(assembly);
            List<EmailAttachment> result = new ArrayList<>();
            for (StagedAttachment s : staged) {
                Path source = s.temporaryPath().toAbsolutePath().normalize();
                if (!Files.exists(source)) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_NOT_FOUND");
                String name = s.id() + "-" + sanitize(s.fileName());
                Files.copy(source, assembly.resolve(name));
                result.add(new EmailAttachment(s.id(), s.fileName(), s.mimeType(), s.sizeBytes(),
                        "attachments/" + messageId + "/" + name, "stored", null));
            }
            Properties meta = new Properties();
            for (EmailAttachment a : result) meta.setProperty(a.id(), String.join("|", a.fileName(), a.mimeType(), Long.toString(a.sizeBytes()), a.relativePath(), a.state()));
            try (var out = Files.newOutputStream(assembly.resolve("metadata.properties"))) { meta.store(out, "attachments"); }
            Files.createDirectories(target.getParent());
            Files.move(assembly, target, StandardCopyOption.ATOMIC_MOVE);
            for (StagedAttachment s : staged) cleanup(s.temporaryPath().getParent());
            return result;
        } catch (EmailAttachmentStoreException e) { cleanup(assembly); throw e;
        } catch (IOException e) { cleanup(assembly); throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID", e); }
    }

    public InputStream open(String messageId, String attachmentId) {
        Path root = attachmentRoot.resolve(messageId == null ? "" : messageId).normalize();
        if (!root.startsWith(attachmentRoot)) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
        try {
            if (!Files.isDirectory(root)) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_NOT_FOUND");
            Properties meta = new Properties();
            try (var in = Files.newInputStream(root.resolve("metadata.properties"))) { meta.load(in); }
            String value = meta.getProperty(attachmentId);
            if (value == null) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_NOT_FOUND");
            String relative = value.split("\\|", -1)[3];
            Path file = dataDir.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
            return Files.newInputStream(file);
        } catch (EmailAttachmentStoreException e) { throw e;
        } catch (IOException e) { throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_NOT_FOUND", e); }
    }

    public long availableBytes() { try { return Files.getFileStore(dataDir).getUsableSpace(); } catch (IOException e) { return 0; } }
    public int reconcile(int maxEntries) { return 0; }
    private long storedAttachmentBytes() throws IOException {
        try (var paths = Files.walk(attachmentRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("metadata.properties"))
                    .mapToLong(path -> {
                        try { return Files.size(path); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                    }).sum();
        } catch (java.io.UncheckedIOException e) { throw e.getCause(); }
    }
    private static String sanitize(String name) { if (name == null || name.isBlank()) return "attachment"; return name.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format("%02x", b)); return s.toString(); }
    private static void cleanup(Path p) { if (p == null) return; try { if (Files.exists(p)) Files.walk(p).sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) {} }); } catch (IOException ignored) {} }
}

class EmailAttachmentStoreException extends RuntimeException {
    private final String errorCode;
    EmailAttachmentStoreException(String errorCode) { super(errorCode); this.errorCode = errorCode; }
    EmailAttachmentStoreException(String errorCode, Throwable cause) { super(errorCode, cause); this.errorCode = errorCode; }
    public String errorCode() { return errorCode; }
}
