package com.crmforlogistics.messagecenter.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.dto.response.ConversationListItemResponse;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.conversation.UnifiedConversationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final UnifiedConversationService service;

    public ConversationController(UnifiedConversationService service) {
        this.service = service;
    }

    @GetMapping
    public Page<ConversationListItemResponse> list(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        UUID userId = SecurityUtil.currentUserId();
        return service.list(userId, search, cursor, limit);
    }
}
