package com.crmforlogistics.messagecenter;

public record EmailAttachment(String id, String fileName, String mimeType, long sizeBytes,
                              String relativePath, String state, String errorCode) {
    public EmailAttachment {
        if (!state.equals("stored") && !state.equals("rejected")) throw new IllegalArgumentException("invalid attachment state");
    }
}

record AttachmentBudget(int maxCount, long maxTotalBytes, long maxStorageBytes) {
    AttachmentBudget {
        if (maxCount < 1 || maxTotalBytes < 0 || maxStorageBytes < 0) throw new IllegalArgumentException("invalid attachment budget");
    }
}

record StagedAttachment(String id, String fileName, String mimeType, long sizeBytes, String sha256, java.nio.file.Path temporaryPath) { }
