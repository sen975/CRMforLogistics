package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class MediaController {
    private final MinioStorage storage;
    private final AttachmentMapper attachmentMapper;

    public MediaController(MinioStorage storage, AttachmentMapper attachmentMapper) {
        this.storage = storage;
        this.attachmentMapper = attachmentMapper;
    }

    @GetMapping("/media/{id}")
    public ResponseEntity<InputStreamResource> getMedia(@PathVariable UUID id) throws Exception {
        return respond(id, false);
    }

    @GetMapping("/media/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id) throws Exception {
        return respond(id, true);
    }

    private ResponseEntity<InputStreamResource> respond(UUID id, boolean download) throws Exception {
        var attachment = attachmentMapper.findReadableById(id, SecurityUtil.currentUserId());
        if (attachment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        var stream = storage.get(attachment.getObjectKey());
        var builder = ResponseEntity.ok()
                .contentType(mediaType(attachment.getMimeType()))
                .header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                        .filename(attachment.getOriginalName(), StandardCharsets.UTF_8)
                        .build().toString());
        if (attachment.getSizeBytes() != null) {
            builder.contentLength(attachment.getSizeBytes());
        }
        return builder.body(new InputStreamResource(stream));
    }

    private static MediaType mediaType(String value) {
        if (value == null || value.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
