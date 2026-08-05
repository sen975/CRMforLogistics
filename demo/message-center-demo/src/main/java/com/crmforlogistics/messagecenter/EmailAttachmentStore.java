package com.crmforlogistics.messagecenter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public class EmailAttachmentStore {
    private static final int MAX_FILE_NAME_BYTES = 128;
    private final Path dataDir;
    private final Path tempRoot;
    private final Path attachmentRoot;
    private final long configuredStorageBytes;
    private final Object lock = new Object();
    private final Map<String, AttachmentBudget> stagedBudgets = new HashMap<>();

    public EmailAttachmentStore(Config config) throws IOException {
        this.dataDir = config.emailDataDir().toAbsolutePath().normalize();
        this.tempRoot = dataDir.resolve("attachment-tmp");
        this.attachmentRoot = dataDir.resolve("attachments");
        this.configuredStorageBytes = config.emailAttachmentStorageMaxBytes();
        Files.createDirectories(tempRoot);
        Files.createDirectories(attachmentRoot);
    }

    public StagedAttachment stage(InputStream input, String fileName, String mimeType, AttachmentBudget budget) {
        try (FileChannel channel = FileChannel.open(dataDir.resolve("attachment-reservations.lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return stageInternal(input, fileName, mimeType, budget);
        } catch (IOException | java.nio.channels.OverlappingFileLockException e) {
            throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_STORAGE_FULL", e);
        }
    }

    private StagedAttachment stageInternal(InputStream input, String fileName, String mimeType, AttachmentBudget budget) {
        if (input == null || budget == null) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
        synchronized (lock) {
            Path requestDir = tempRoot.resolve(UUID.randomUUID().toString());
            try {
                long occupiedBefore = storedAttachmentBytes() + stagedBytes();
                Files.createDirectories(requestDir);
                String id = UUID.randomUUID().toString();
                Path target = requestDir.resolve(id + ".bin");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long size = copyAndDigest(input, target, digest, budget.maxTotalBytes());
                if (occupiedBefore + size > storageLimit(budget)) {
                    throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_STORAGE_FULL");
                }
                StagedAttachment staged = new StagedAttachment(id, sanitize(fileName), safeMimeType(mimeType),
                        size, hex(digest.digest()), target);
                stagedBudgets.put(id, budget);
                return staged;
            } catch (EmailAttachmentStoreException e) {
                cleanup(requestDir);
                throw e;
            } catch (IOException | NoSuchAlgorithmException | InvalidPathException e) {
                cleanup(requestDir);
                throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID", e);
            }
        }
    }

    public List<EmailAttachment> publish(String messageId, List<StagedAttachment> staged) {
        synchronized (lock) {
            validateMessageId(messageId);
            if (staged == null) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
            Path target = attachmentRoot.resolve(messageId).normalize();
            if (!target.startsWith(attachmentRoot) || Files.exists(target)) {
                throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
            }
            for (StagedAttachment attachment : staged) if (attachment == null) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
            List<StagedAttachment> batch = List.copyOf(staged);
            Path assembly = tempRoot.resolve("publish-" + UUID.randomUUID());
            boolean published = false;
            try {
                AttachmentBudget budget = validateBatch(batch);
                if (storedAttachmentBytes() + stagedBytes() > storageLimit(budget)) {
                    throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_STORAGE_FULL");
                }
                Files.createDirectories(assembly);
                List<EmailAttachment> result = new ArrayList<>();
                Properties metadata = new Properties();
                for (StagedAttachment attachment : batch) {
                    Path source = validateStagedPath(attachment);
                    if (!Files.isRegularFile(source)) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_NOT_FOUND");
                    String storedName = attachment.id() + "-" + sanitize(attachment.fileName());
                    Files.copy(source, assembly.resolve(storedName));
                    metadata.setProperty(attachment.id() + ".path", storedName);
                    result.add(new EmailAttachment(attachment.id(), attachment.fileName(), attachment.mimeType(),
                            attachment.sizeBytes(), "attachments/" + messageId + "/" + storedName, "stored", null));
                }
                try (OutputStream out = Files.newOutputStream(assembly.resolve("metadata.properties"))) {
                    metadata.store(out, "attachments");
                }
                Files.createDirectories(target.getParent());
                Files.move(assembly, target, StandardCopyOption.ATOMIC_MOVE);
                published = true;
                return result;
            } catch (EmailAttachmentStoreException e) {
                throw e;
            } catch (IOException | InvalidPathException e) {
                throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID", e);
            } finally {
                cleanup(assembly);
                for (StagedAttachment attachment : batch) {
                    if (!published || Files.exists(attachment.temporaryPath())) cleanup(attachment.temporaryPath().getParent());
                    stagedBudgets.remove(attachment.id());
                }
            }
        }
    }

    public InputStream open(String messageId, String attachmentId) {
        synchronized (lock) {
            try {
                validateMessageId(messageId);
                if (attachmentId == null || !attachmentId.matches("[0-9a-fA-F-]{36}")) {
                    throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
                }
                Path root = attachmentRoot.resolve(messageId).normalize();
                if (!root.startsWith(attachmentRoot) || !Files.isDirectory(root)) {
                    throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_NOT_FOUND");
                }
                if (Files.isSymbolicLink(root)) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
                for (Path component = attachmentRoot; component != null && !component.equals(root); component = component.resolve(component.relativize(root).getName(0)).normalize()) {
                    if (Files.isSymbolicLink(component)) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
                }
                Properties metadata = new Properties();
                try (InputStream in = Files.newInputStream(root.resolve("metadata.properties"))) {
                    metadata.load(in);
                }
                String storedName = metadata.getProperty(attachmentId + ".path");
                if (storedName == null) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_NOT_FOUND");
                Path file = root.resolve(storedName).normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                    throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
                }
                return Files.newInputStream(file);
            } catch (EmailAttachmentStoreException e) {
                throw e;
            } catch (IOException | InvalidPathException | NullPointerException e) {
                throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID", e);
            }
        }
    }

    public long availableBytes() {
        synchronized (lock) {
            try {
                return Math.max(0, configuredStorageBytes - storedAttachmentBytes() - stagedBytes());
            } catch (IOException e) {
                throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID", e);
            }
        }
    }

    public int reconcile(int maxEntries) {
        if (maxEntries <= 0) return 0;
        synchronized (lock) {
            try (var entries = Files.list(tempRoot)) {
                long cutoff = System.currentTimeMillis() - 3_600_000L;
                List<Path> stale = entries.filter(path -> {
                    try { return Files.getLastModifiedTime(path).toMillis() < cutoff; }
                    catch (IOException e) { return false; }
                }).sorted().limit(maxEntries).toList();
                stale.forEach(EmailAttachmentStore::cleanup);
                return stale.size();
            } catch (IOException e) {
                throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID", e);
            }
        }
    }

    private AttachmentBudget validateBatch(List<StagedAttachment> batch) throws IOException {
        if (batch.isEmpty()) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
        AttachmentBudget effective = null;
        long total = 0;
        for (StagedAttachment attachment : batch) {
            if (attachment == null || !stagedBudgets.containsKey(attachment.id())) {
                throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_NOT_FOUND");
            }
            AttachmentBudget budget = stagedBudgets.get(attachment.id());
            effective = effective == null ? budget : new AttachmentBudget(
                    Math.min(effective.maxCount(), budget.maxCount()),
                    Math.min(effective.maxTotalBytes(), budget.maxTotalBytes()),
                    Math.min(effective.maxStorageBytes(), budget.maxStorageBytes()));
            try { total = Math.addExact(total, attachment.sizeBytes()); }
            catch (ArithmeticException overflow) { throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_SIZE_LIMIT", overflow); }
        }
        if (batch.size() > effective.maxCount() || total > effective.maxTotalBytes()) {
            throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_SIZE_LIMIT");
        }
        return effective;
    }

    private Path validateStagedPath(StagedAttachment attachment) {
        if (attachment.temporaryPath() == null) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
        Path source = attachment.temporaryPath().toAbsolutePath().normalize();
        if (!source.startsWith(tempRoot) || !source.getFileName().toString().equals(attachment.id() + ".bin")) {
            throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
        }
        return source;
    }

    private long copyAndDigest(InputStream input, Path target, MessageDigest digest, long maxBytes) throws IOException {
        long size = 0;
        try (InputStream in = input; OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) >= 0;) {
                if (read == 0) continue;
                size += read;
                if (size > maxBytes) throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_SIZE_LIMIT");
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        return size;
    }

    private long storageLimit(AttachmentBudget budget) { return Math.min(configuredStorageBytes, budget.maxStorageBytes()); }
    private long storedAttachmentBytes() throws IOException { return bytesBelow(attachmentRoot, false); }
    private long stagedBytes() throws IOException { return bytesBelow(tempRoot, true); }
    private static long bytesBelow(Path root, boolean stagedOnly) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("metadata.properties"))
                    .filter(path -> !stagedOnly || path.getFileName().toString().endsWith(".bin"))
                    .mapToLong(path -> { try { return Files.size(path); } catch (IOException e) { throw new UncheckedIOException(e); } })
                    .sum();
        } catch (UncheckedIOException e) { throw e.getCause(); }
    }
    private static void validateMessageId(String messageId) {
        if (messageId == null || messageId.isBlank() || messageId.equals(".") || messageId.equals("..")
                || messageId.contains("/") || messageId.contains("\\") || messageId.chars().anyMatch(Character::isISOControl)) {
            throw new EmailAttachmentStoreException("EMAIL_ATTACHMENT_PATH_INVALID");
        }
    }
    private static String safeMimeType(String mimeType) { return mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType; }
    private static String sanitize(String name) {
        String sanitized = name == null || name.isBlank() ? "attachment" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.length() > MAX_FILE_NAME_BYTES ? sanitized.substring(0, MAX_FILE_NAME_BYTES) : sanitized;
    }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format("%02x", b)); return value.toString(); }
    private static void cleanup(Path directory) { if (directory == null) return; try (var paths = Files.walk(directory)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); } catch (IOException ignored) { } }
}

class EmailAttachmentStoreException extends RuntimeException {
    private final String errorCode;
    EmailAttachmentStoreException(String errorCode) { super(errorCode); this.errorCode = errorCode; }
    EmailAttachmentStoreException(String errorCode, Throwable cause) { super(errorCode, cause); this.errorCode = errorCode; }
    public String errorCode() { return errorCode; }
}
