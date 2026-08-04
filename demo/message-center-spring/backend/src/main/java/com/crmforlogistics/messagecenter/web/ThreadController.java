package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.ThreadResponse;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.message.ThreadService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ThreadController {
    private final ThreadService threadService;

    public ThreadController(ThreadService threadService) {
        this.threadService = threadService;
    }

    @GetMapping("/threads")
    public ThreadResponse listThreads(
            @RequestParam UUID contactId,
            @RequestParam(required = false) String channelType,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") int limit) {
        return threadService.threadPage(
                SecurityUtil.currentUserId(), contactId, channelType, cursor, limit);
    }
}
