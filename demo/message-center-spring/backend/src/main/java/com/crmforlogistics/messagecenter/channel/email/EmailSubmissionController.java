package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/email/submissions")
public class EmailSubmissionController {
    private final EmailSendService sendService;

    public EmailSubmissionController(EmailSendService sendService) {
        this.sendService = sendService;
    }

    @GetMapping("/unknown")
    public ResponseEntity<List<EmailSendService.SubmissionOutcome>> unknown(
            @RequestParam(defaultValue = "50") int limit) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(sendService.listUnknownSubmissions(
                SecurityUtil.currentUserId(), Math.max(1, Math.min(limit, 100))));
    }
}
