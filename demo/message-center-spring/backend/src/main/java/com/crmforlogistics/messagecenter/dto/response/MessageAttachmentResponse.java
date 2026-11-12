package com.crmforlogistics.messagecenter.dto.response;

import com.crmforlogistics.messagecenter.entity.AttachmentEntity;

import java.util.UUID;

public record MessageAttachmentResponse(
        UUID id,
        String mediaKind,
        String mimeType,
        String fileName,
        Long sizeBytes
) {
    public static MessageAttachmentResponse from(AttachmentEntity attachment) {
        return new MessageAttachmentResponse(
                attachment.getId(),
                attachment.getMediaKind(),
                attachment.getMimeType(),
                attachment.getOriginalName(),
                attachment.getSizeBytes()
        );
    }
}
