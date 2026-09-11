package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.ContactMemoryResponse;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/contacts")
public class ContactMemoryController {
    private final ContactMemoryQueryService memory;

    public ContactMemoryController(ContactMemoryQueryService memory) {
        this.memory = memory;
    }

    @GetMapping("/{contactId}/memory")
    public ResponseEntity<ContactMemoryResponse> get(@PathVariable UUID contactId) {
        UUID ownerUserId = SecurityUtil.currentUserId();
        return memory.findForOwner(ownerUserId, contactId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
