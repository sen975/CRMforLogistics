package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

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
            EmailSendService.SendResult result = currentUserIdOrNull() == null
                    ? sendService.send(to, subject, emailBody)
                    : sendService.send(SecurityUtil.currentUserId(), to, subject, emailBody);
            return ResponseEntity.ok(result);
        } catch (EmailException exception) {
            throw exception;
        } catch (Exception e) {
            log.error("Email send failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/send", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> sendMultipart(@RequestParam String to,
                                           @RequestParam(defaultValue = "") String subject,
                                           @RequestParam(defaultValue = "") String body,
                                           @RequestPart(value = "file", required = false) List<MultipartFile> files) throws Exception {
        var inputs = (files == null ? List.<MultipartFile>of() : files).stream()
                .map(file -> new EmailAttachmentInput(
                        file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename(),
                        file.getContentType(), file.getSize(), file::getInputStream))
                .toList();
        var ownerId = currentUserIdOrNull();
        return ResponseEntity.ok(ownerId == null
                ? sendService.send(to, subject, body, inputs)
                : sendService.send(ownerId, to, subject, body, inputs));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            EmailSyncService.SyncResult result = auth == null || !auth.isAuthenticated()
                    || "anonymousUser".equals(auth.getName())
                    ? syncService.receiveLatest()
                    : syncService.receiveLatest(SecurityUtil.currentUserId());
            return ResponseEntity.ok(result);
        } catch (EmailException exception) {
            throw exception;
        } catch (Exception e) {
            log.error("Email sync failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private java.util.UUID currentUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) return null;
        return SecurityUtil.currentUserId();
    }

}
