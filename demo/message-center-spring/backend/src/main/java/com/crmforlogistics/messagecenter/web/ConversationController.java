package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.ConversationPageResponse;
import com.crmforlogistics.messagecenter.dto.request.ConversationPreferenceRequest;
import com.crmforlogistics.messagecenter.dto.request.SearchMode;
import com.crmforlogistics.messagecenter.dto.response.ConversationPreferenceResponse;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.conversation.UnifiedConversationService;
import com.crmforlogistics.messagecenter.service.conversation.ConversationPreferenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final UnifiedConversationService service;
    private final ConversationPreferenceService preferences;

    public ConversationController(UnifiedConversationService service,
                                  ConversationPreferenceService preferences) {
        this.service = service;
        this.preferences = preferences;
    }

    @GetMapping
    public ConversationPageResponse list(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "searchMode", required = false) String rawSearchMode,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        UUID userId = SecurityUtil.currentUserId();
        return service.list(userId, search, SearchMode.parse(rawSearchMode), cursor, limit);
    }

    @PostMapping("/preferences/pin")
    public ConversationPreferenceResponse togglePin(@RequestBody ConversationPreferenceRequest request) {
        return preferences.togglePinned(SecurityUtil.currentUserId(), request.targetType(), request.targetId());
    }

    @PostMapping("/preferences/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hide(@RequestBody ConversationPreferenceRequest request) {
        preferences.hide(SecurityUtil.currentUserId(), request.targetType(), request.targetId());
    }

    @PostMapping("/preferences/order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@RequestBody ConversationPreferenceRequest.OrderRequest request) {
        if (request == null) throw new IllegalArgumentException("conversation order request is invalid");
        preferences.reorder(SecurityUtil.currentUserId(), request.sourceType(), request.sourceId(),
                request.targetType(), request.targetId(), request.placement());
    }
}
