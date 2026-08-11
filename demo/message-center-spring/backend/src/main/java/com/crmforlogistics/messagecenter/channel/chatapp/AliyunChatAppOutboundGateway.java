package com.crmforlogistics.messagecenter.channel.chatapp;

import org.springframework.stereotype.Component;
import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
public class AliyunChatAppOutboundGateway implements ChatAppOutboundGateway {
    private final ChatAppSendService sendService;
    private final MinioStorage minioStorage;
    private final AttachmentMapper attachmentMapper;

    public AliyunChatAppOutboundGateway(ChatAppSendService sendService,
                                        MinioStorage minioStorage,
                                        AttachmentMapper attachmentMapper) {
        this.sendService = sendService;
        this.minioStorage = minioStorage;
        this.attachmentMapper = attachmentMapper;
    }

    @Override
    public Submission submit(Command command) throws Exception {
        try {
            ChatAppSendService.SendResult result = switch (command.kind()) {
                case "text" -> sendService.sendText(
                        required(command.content(), "to"),
                        stringValue(command.content().get("text")),
                        command.clientRequestId());
                case "template" -> sendService.sendTemplate(
                        required(command.content(), "to"),
                        required(command.content(), "templateCode"),
                        stringValue(command.content().get("templateName")),
                        stringValue(command.content().get("languageCode")),
                        stringMap(command.content().get("templateParams")),
                        command.clientRequestId());
                case "image", "video", "document" -> sendMedia(command);
                default -> throw new IllegalArgumentException(
                        "CHATAPP_OUTBOX_KIND_NOT_SUPPORTED: " + command.kind());
            };
            return new Submission(result.messageId());
        } catch (Exception e) {
            Throwable cause = rootCause(e);
            if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException) {
                throw new SubmissionUnknownException("CAMS_SUBMISSION_RESULT_UNKNOWN", e);
            }
            if (e instanceof IllegalArgumentException) throw e;
            String error = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (error.contains("429") || error.contains("throttl") || error.contains("rate limit")
                    || error.contains("service unavailable")) {
                throw new RetryableException("CAMS_RETRYABLE_REJECTION", e);
            }
            throw new SubmissionUnknownException("CAMS_SUBMISSION_RESULT_UNKNOWN", e);
        }
    }

    private ChatAppSendService.SendResult sendMedia(Command command) throws Exception {
        String objectKey = required(command.content(), "objectKey");
        if (!attachmentMapper.existsReadyForMessage(command.messageId(), objectKey)) {
            throw new IllegalArgumentException("CHATAPP_MEDIA_ATTACHMENT_NOT_READY");
        }
        byte[] bytes;
        try (InputStream input = minioStorage.get(objectKey)) {
            bytes = input.readNBytes(64 * 1024 * 1024 + 1);
        }
        if (bytes.length > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("CHATAPP_MEDIA_TOO_LARGE");
        }
        return sendService.sendMedia(
                required(command.content(), "to"),
                command.kind(),
                bytes,
                stringValue(command.content().get("fileName")),
                stringValue(command.content().get("contentType")),
                stringValue(command.content().get("caption")),
                command.clientRequestId());
    }

    private static String required(Map<String, Object> content, String key) {
        String value = stringValue(content.get(key));
        if (value.isBlank()) throw new IllegalArgumentException("CHATAPP_" + key.toUpperCase() + "_REQUIRED");
        return value;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), stringValue(item)));
        return result;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }
}
