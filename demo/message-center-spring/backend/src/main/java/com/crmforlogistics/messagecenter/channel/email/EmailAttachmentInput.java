package com.crmforlogistics.messagecenter.channel.email;

import java.io.IOException;
import java.io.InputStream;

public record EmailAttachmentInput(
        String fileName,
        String mimeType,
        long declaredSize,
        InputStreamOpener opener
) {
    public EmailAttachmentInput {
        if (fileName == null || opener == null) {
            throw new IllegalArgumentException("Attachment filename and opener are required");
        }
        if (declaredSize < -1) {
            throw new IllegalArgumentException("Attachment size must be unknown or non-negative");
        }
    }

    @FunctionalInterface
    public interface InputStreamOpener {
        InputStream open() throws IOException;
    }
}
