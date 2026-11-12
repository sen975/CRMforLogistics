package com.crmforlogistics.messagecenter;

import java.util.List;
import java.util.Objects;

public record EmailSendCommand(String to, String subject, String body, String messageId,
                               List<StagedAttachment> attachments) {
    public EmailSendCommand {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        messageId = Objects.requireNonNull(messageId, "messageId");
    }
}
