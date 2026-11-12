package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.request.ContactGroupRequest;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.contact.ContactGroupService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for contact-group operations: merge, split, and listing.
 *
 * <p>Ported from the old project's {@code App.java} route handlers:
 * {@code GET /api/contact-groups}, {@code POST /api/contact-groups/merge},
 * {@code POST /api/contact-groups/split}.
 */
@RestController
@RequestMapping("/api/contact-groups")
public class ContactGroupController {

    private final ContactGroupService contactGroupService;

    public ContactGroupController(ContactGroupService contactGroupService) {
        this.contactGroupService = contactGroupService;
    }

    /**
     * List contact groups.
     * Currently a stub returning an empty list until group management is ported.
     *
     * @return an empty list
     */
    @GetMapping
    public List<Object> list() {
        return Collections.emptyList();
    }

    /**
     * Merge two contacts: move all identities from {@code sourceContactId} into
     * {@code targetContactId}, then mark the source as merged.
     *
     * @param request request body containing {@code sourceContactId} and {@code targetContactId}
     */
    @PostMapping("/merge")
    public void merge(@RequestBody ContactGroupRequest request) {
        UUID userId = SecurityUtil.currentUserId();
        contactGroupService.merge(request.sourceContactId(),
                request.targetContactId(), userId);
    }

    /**
     * Split a single identity into a new contact.
     *
     * @param request request body containing {@code identityId} and {@code newContactName}
     */
    @PostMapping("/split")
    public void split(@RequestBody ContactGroupRequest request) {
        UUID userId = SecurityUtil.currentUserId();
        contactGroupService.split(request.identityId(),
                request.newContactName(), userId);
    }
}
