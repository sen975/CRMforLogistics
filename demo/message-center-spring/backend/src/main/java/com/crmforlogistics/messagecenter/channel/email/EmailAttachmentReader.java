package com.crmforlogistics.messagecenter.channel.email;

import jakarta.mail.internet.MimeUtility;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EmailAttachmentReader {
    private static final int BUFFER_SIZE = 8 * 1024;
    private final int maxCount;
    private final long maxTotalBytes;

    public EmailAttachmentReader(int maxCount, long maxTotalBytes) {
        if (maxCount < 1 || maxTotalBytes < 1) {
            throw new IllegalArgumentException("Attachment limits must be positive");
        }
        this.maxCount = maxCount;
        this.maxTotalBytes = maxTotalBytes;
    }

    public List<EmailAttachmentPayload> read(List<EmailAttachmentInput> inputs) {
        if (inputs == null) {
            throw new IllegalArgumentException("Attachment inputs are required");
        }
        if (inputs.size() > maxCount) {
            throw new EmailException("EMAIL_ATTACHMENT_COUNT_LIMIT", "Too many email attachments");
        }
        var result = new ArrayList<EmailAttachmentPayload>(inputs.size());
        long total = 0;
        for (EmailAttachmentInput input : inputs) {
            try (InputStream stream = input.opener().open()) {
                var output = new ByteArrayOutputStream();
                var buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (read == 0) continue;
                    if (total > maxTotalBytes - read) {
                        throw new EmailException("EMAIL_ATTACHMENT_SIZE_LIMIT", "Email attachment size limit exceeded");
                    }
                    output.write(buffer, 0, read);
                    total += read;
                }
                var bytes = output.toByteArray();
                var mimeType = normalizeMimeType(input.mimeType());
                result.add(new EmailAttachmentPayload(
                        normalizeFileName(input.fileName()), mimeType, mediaKind(mimeType), bytes, sha256(bytes)));
            } catch (EmailException ex) {
                throw ex;
            } catch (IOException | RuntimeException ex) {
                throw new EmailException("EMAIL_ATTACHMENT_READ_FAILED", "Unable to read email attachment", ex);
            }
        }
        return List.copyOf(result);
    }

    /** 收件补采使用：单个 MIME 部件失败时保留其他可读附件，预算耗尽后停止。 */
    public List<EmailAttachmentPayload> readAvailable(List<EmailAttachmentInput> inputs) {
        if (inputs == null) throw new IllegalArgumentException("Attachment inputs are required");
        var result = new ArrayList<EmailAttachmentPayload>();
        long total = 0;
        for (EmailAttachmentInput input : inputs) {
            if (result.size() >= maxCount || total >= maxTotalBytes) break;
            try {
                long remaining = maxTotalBytes - total;
                var payloads = new EmailAttachmentReader(1, remaining).read(List.of(input));
                if (!payloads.isEmpty()) {
                    result.add(payloads.get(0));
                    total += payloads.get(0).sizeBytes();
                }
            } catch (EmailException ex) {
                if ("EMAIL_ATTACHMENT_SIZE_LIMIT".equals(ex.code())) break;
            }
        }
        return List.copyOf(result);
    }

    static String normalizeFileName(String raw) {
        String name = decodeFileName(raw).replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\u0000-\\u001f\\u007f]", "_").trim();
        if (name.isEmpty()) name = "attachment";
        while (name.getBytes(StandardCharsets.UTF_8).length > 255) {
            int codePoints = name.codePointCount(0, name.length());
            name = name.substring(0, name.offsetByCodePoints(0, codePoints - 1));
        }
        return name;
    }

    private static String decodeFileName(String raw) {
        if (raw == null || raw.isBlank()) return "attachment";
        try {
            return MimeUtility.decodeText(raw);
        } catch (Exception ignored) {
            return raw;
        }
    }

    static String normalizeMimeType(String raw) {
        if (raw == null || raw.isBlank() || !raw.contains("/")) return "application/octet-stream";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
                ? value : "application/octet-stream";
    }

    static String mediaKind(String mimeType) {
        if (mimeType.startsWith("image/")) return "image";
        if (mimeType.startsWith("video/")) return "video";
        if (mimeType.startsWith("audio/")) return "audio";
        String lower = mimeType.toLowerCase(Locale.ROOT);
        if (lower.contains("zip") || lower.contains("rar") || lower.contains("7z") || lower.contains("gzip")
                || lower.contains("compressed") || lower.contains("tar")) return "archive";
        if (lower.startsWith("text/") || lower.contains("pdf") || lower.contains("msword")
                || lower.contains("wordprocessing") || lower.contains("spreadsheet")
                || lower.contains("presentation") || lower.contains("officedocument")) return "document";
        return "other";
    }

    private static String sha256(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            var builder = new StringBuilder(64);
            for (byte value : digest) builder.append(String.format("%02x", value));
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
