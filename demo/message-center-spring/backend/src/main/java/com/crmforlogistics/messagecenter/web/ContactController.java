package com.crmforlogistics.messagecenter.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.crmforlogistics.messagecenter.dto.request.ContactGroupRequest;
import com.crmforlogistics.messagecenter.dto.response.ContactResponse;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.contact.ContactGroupService;
import com.crmforlogistics.messagecenter.service.contact.ContactService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * REST controller for contact listing, detail, remark, and profile operations.
 *
 * <p>Ported from the old project's {@code App.java} route handlers:
 * {@code GET /api/contacts}, {@code GET /api/contacts/{id}},
 * {@code POST /api/contacts/{id}/remark}, {@code POST /api/contacts/{id}/profile}.
 */
@RestController
@RequestMapping("/api/contacts")
public class ContactController {

    private final ContactService contactService;
    private final ContactGroupService contactGroupService;

    public ContactController(ContactService contactService,
                             ContactGroupService contactGroupService) {
        this.contactService = contactService;
        this.contactGroupService = contactGroupService;
    }

    /**
     * List contacts for the current user with optional search and cursor pagination.
     *
     * @param search               optional search string (ilike on display_name + remark)
     * @param beforeLastMessageAt  cursor: sort_at timestamp (ISO-8601)
     * @param beforeId             cursor: contact id
     * @param page                 page number (1-based, default 1)
     * @param size                 page size (1-100, default 20)
     * @return paginated list of contacts enriched with channel types and last message info
     */
    @GetMapping
    public IPage<ContactResponse> list(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "beforeLastMessageAt", required = false) Instant beforeLastMessageAt,
            @RequestParam(value = "beforeId", required = false) UUID beforeId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        UUID userId = SecurityUtil.currentUserId();
        return contactService.listForUser(userId, search,
                beforeLastMessageAt, beforeId, page, size);
    }

    /**
     * Get a single contact by id, enriched with channel types, last message info,
     * and unread count.
     *
     * @param id the contact UUID
     * @return the contact response
     */
    @GetMapping("/{id}")
    public ContactResponse getById(@PathVariable UUID id) {
        UUID userId = SecurityUtil.currentUserId();
        return contactService.getById(userId, id);
    }

    /**
     * Update the remark on a contact.
     *
     * @param id      the contact UUID
     * @param request request body containing the {@code remark} field
     */
    @PostMapping("/{id}/remark")
    public void updateRemark(@PathVariable UUID id,
                             @RequestBody ContactGroupRequest request) {
        UUID userId = SecurityUtil.currentUserId();
        contactGroupService.updateRemark(id, request.remark(), userId);
    }

    /**
     * Update profile fields (display name, role title) on a contact.
     *
     * @param id      the contact UUID
     * @param request request body containing {@code displayName} and {@code roleTitle}
     */
    @PostMapping("/{id}/profile")
    public void updateProfile(@PathVariable UUID id,
                              @RequestBody ContactGroupRequest request) {
        UUID userId = SecurityUtil.currentUserId();
        contactGroupService.updateProfile(id, request.displayName(),
                request.roleTitle(), userId);
    }

    /**
     * Mark all messages for a contact as read.
     *
     * @param id the contact UUID
     */
    @PostMapping("/{id}/mark-read")
    public void markAsRead(@PathVariable UUID id) {
        contactService.markAsRead(SecurityUtil.currentUserId(), id);
    }
}
