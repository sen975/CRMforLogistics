package com.crmforlogistics.messagecenter.channel.email;

public record EmailAttachmentPayload(
        String fileName,
        String mimeType,
        String mediaKind,
        byte[] bytes,
        String sha256
) {
    public long sizeBytes() {
        return bytes.length;
    }
}
