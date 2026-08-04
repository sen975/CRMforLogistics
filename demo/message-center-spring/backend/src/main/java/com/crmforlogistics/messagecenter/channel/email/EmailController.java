package com.crmforlogistics.messagecenter.channel.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/email")
public class EmailController {

    private static final Logger log = LoggerFactory.getLogger(EmailController.class);

    private final EmailSendService sendService;
    private final EmailSyncService syncService;

    public EmailController(EmailSendService sendService, EmailSyncService syncService) {
        this.sendService = sendService;
        this.syncService = syncService;
    }

    @PostMapping("/send")
    public ResponseEntity<?> send(@RequestBody Map<String, String> body) {
        try {
            String to = body.get("to");
            String subject = body.getOrDefault("subject", "");
            String emailBody = body.getOrDefault("body", "");
            EmailSendService.SendResult result = sendService.send(to, subject, emailBody);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Email send failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync() {
        try {
            EmailSyncService.SyncResult result = syncService.receiveLatest();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Email sync failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
