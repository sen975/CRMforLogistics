package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MediaController {
    private final MinioStorage storage;

    public MediaController(MinioStorage storage) {
        this.storage = storage;
    }

    @GetMapping("/media/{id}")
    public ResponseEntity<InputStreamResource> getMedia(@PathVariable String id) throws Exception {
        var stream = storage.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }
}
