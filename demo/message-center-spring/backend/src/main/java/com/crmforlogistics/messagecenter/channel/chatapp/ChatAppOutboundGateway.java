package com.crmforlogistics.messagecenter.channel.chatapp;

import java.util.Map;
import java.util.UUID;

public interface ChatAppOutboundGateway {
    Submission submit(Command command) throws Exception;

    record Command(UUID channelAccountId, UUID messageId, String clientRequestId,
                   String kind, Map<String, Object> content,
                   ChatAppSubmissionContext submissionContext) {
        public Command(UUID channelAccountId, UUID messageId, String clientRequestId,
                       String kind, Map<String, Object> content) {
            this(channelAccountId, messageId, clientRequestId, kind, content, null);
        }
    }

    record Submission(String providerMessageId) {}

    class RetryableException extends Exception {
        public RetryableException(String message) { super(message); }
        public RetryableException(String message, Throwable cause) { super(message, cause); }
    }

    class SubmissionUnknownException extends Exception {
        public SubmissionUnknownException(String message) { super(message); }
        public SubmissionUnknownException(String message, Throwable cause) { super(message, cause); }
    }
}
