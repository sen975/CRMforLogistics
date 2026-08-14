package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.service.wecom.WeComStartupGate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
@RequestMapping("/api/wecom")
public class WeComController {
    private final WeComCallbackCodec codec;
    private final WeComInstallationService installationService;
    private final WeComSendService sendService;
    private final WeComStartupGate startupGate;

    public WeComController(WeComCallbackCodec codec,
                            WeComInstallationService installationService,
                            WeComSendService sendService,
                            WeComStartupGate startupGate) {
        this.codec = codec;
        this.installationService = installationService;
        this.sendService = sendService;
        this.startupGate = startupGate;
    }

    @PostMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestBody String body) {
        try {
            startupGate.requireOpen();
            var decoded = codec.decode(msgSignature, timestamp, nonce, body);
            installationService.handleCallback(decoded);
            return ResponseEntity.ok("success");
        } catch (WeComException e) {
            return ResponseEntity.status(e.httpStatus())
                    .body(e.getMessage());
        }
    }

    @GetMapping("/callback")
    public ResponseEntity<String> verifyCallback(
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestParam("echostr") String echostr) {
        try {
            startupGate.requireOpen();
            String decrypted = codec.verifyAndDecryptEcho(
                    msgSignature, timestamp, nonce, echostr);
            return ResponseEntity.ok(decrypted);
        } catch (WeComException e) {
            return ResponseEntity.status(e.httpStatus()).build();
        }
    }

    @PostMapping("/send")
    public ResponseEntity<?> send(@RequestBody Map<String, Object> body) {
        try {
            String corpId = (String) body.get("corpId");
            String agentId = (String) body.get("agentId");
            String to = (String) body.get("to");
            String text = (String) body.getOrDefault("text", "");
            var result = sendService.send(corpId, agentId, to, text);
            return ResponseEntity.ok(result);
        } catch (WeComException e) {
            return ResponseEntity.status(e.httpStatus())
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
