package com.crmforlogistics.messagecenter;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUpload;
import org.apache.commons.fileupload.FileUploadBase;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.RequestContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class EmailMultipartParser {
    private static final long OVERHEAD_BYTES = 128 * 1024L;
    private static final int MAX_FIELD_BYTES = 8192;

    private final Config config;
    private final EmailAttachmentStore store;

    EmailMultipartParser(Config config) throws IOException {
        this(config, new EmailAttachmentStore(config));
    }

    EmailMultipartParser(Config config, EmailAttachmentStore store) {
        this.config = config;
        this.store = store;
    }

    EmailSendCommand parse(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/form-data")
                || !contentType.contains("boundary=")) {
            throw new EmailMultipartException("EMAIL_MULTIPART_REQUIRED", 400);
        }
        FileUpload upload = new FileUpload();
        upload.setFileSizeMax(config.emailAttachmentMaxTotalBytes());
        upload.setSizeMax(config.emailAttachmentMaxTotalBytes() + OVERHEAD_BYTES + 3L * MAX_FIELD_BYTES);
        upload.setPartHeaderSizeMax(8192);
        Map<String, String> fields = new LinkedHashMap<>();
        List<StagedAttachment> staged = new ArrayList<>();
        long totalBytes = 0;
        boolean fileSeen = false;
        try {
            FileItemIterator items = upload.getItemIterator(new ExchangeRequestContext(exchange));
            while (items.hasNext()) {
                FileItemStream item = items.next();
                String name = item.getFieldName();
                if (item.isFormField()) {
                    if (fileSeen || !allowedField(name) || fields.containsKey(name)) {
                        throw new EmailMultipartException("EMAIL_MULTIPART_FIELD_INVALID", 400);
                    }
                    fields.put(name, readField(item.openStream()));
                    continue;
                }
                if (!"file".equals(name)) throw new EmailMultipartException("EMAIL_MULTIPART_FIELD_INVALID", 400);
                fileSeen = true;
                if (staged.size() >= config.emailAttachmentMaxCount()) {
                    throw new EmailMultipartException("EMAIL_ATTACHMENT_COUNT_LIMIT", 413);
                }
                StagedAttachment attachment = store.stage(item.openStream(), item.getName(), item.getContentType(),
                        new AttachmentBudget(config.emailAttachmentMaxCount(), config.emailAttachmentMaxTotalBytes(),
                                config.emailAttachmentStorageMaxBytes()));
                totalBytes += attachment.sizeBytes();
                if (totalBytes > config.emailAttachmentMaxTotalBytes()) {
                    store.discard(List.of(attachment));
                    throw new EmailMultipartException("EMAIL_ATTACHMENT_SIZE_LIMIT", 413);
                }
                staged.add(attachment);
            }
        } catch (EmailMultipartException exception) {
            store.discard(staged);
            throw exception;
        } catch (FileUploadBase.SizeLimitExceededException | FileUploadBase.FileSizeLimitExceededException exception) {
            store.discard(staged);
            throw new EmailMultipartException("EMAIL_ATTACHMENT_SIZE_LIMIT", 413, exception);
        } catch (EmailAttachmentStoreException exception) {
            store.discard(staged);
            int status = "EMAIL_ATTACHMENT_STORAGE_FULL".equals(exception.errorCode()) ? 507 : 413;
            throw new EmailMultipartException(exception.errorCode(), status, exception);
        } catch (FileUploadException | RuntimeException exception) {
            store.discard(staged);
            throw new EmailMultipartException("EMAIL_MULTIPART_FIELD_INVALID", 400, exception);
        }
        String to = fields.getOrDefault("to", "").trim();
        String subject = fields.getOrDefault("subject", "");
        if (to.isBlank() || subject.isBlank()) {
            store.discard(staged);
            throw new EmailMultipartException("EMAIL_MULTIPART_FIELD_INVALID", 400);
        }
        return new EmailSendCommand(to, subject, fields.getOrDefault("body", ""), UUID.randomUUID().toString(), staged);
    }

    private static boolean allowedField(String name) {
        return "to".equals(name) || "subject".equals(name) || "body".equals(name);
    }

    private static String readField(InputStream input) throws IOException, EmailMultipartException {
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int total = 0;
            for (int read; (read = in.read(buffer)) >= 0;) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_FIELD_BYTES) throw new EmailMultipartException("EMAIL_MULTIPART_FIELD_INVALID", 400);
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private record ExchangeRequestContext(HttpExchange exchange) implements RequestContext {
        @Override public String getCharacterEncoding() { return StandardCharsets.UTF_8.name(); }
        @Override public int getContentLength() {
            try { return Integer.parseInt(exchange.getRequestHeaders().getFirst("Content-Length")); }
            catch (Exception ignored) { return -1; }
        }
        @Override public String getContentType() { return exchange.getRequestHeaders().getFirst("Content-Type"); }
        @Override public InputStream getInputStream() { return exchange.getRequestBody(); }
    }
}

final class EmailMultipartException extends IOException {
    private final String code;
    private final int status;
    EmailMultipartException(String code, int status) { super(code); this.code = code; this.status = status; }
    EmailMultipartException(String code, int status, Throwable cause) { super(code, cause); this.code = code; this.status = status; }
    String code() { return code; }
    int status() { return status; }
}
