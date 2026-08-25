package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.service.wecom.WeComAuthorizationService;
import com.crmforlogistics.messagecenter.service.wecom.WeComStartupGate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@RestController
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComController {
    private static final Logger log = LoggerFactory.getLogger(WeComController.class);
    private final WeComCallbackCodec codec;
    private final WeComAuthorizationService authorizationService;
    private final WeComSendService sendService;
    private final WeComStartupGate startupGate;

    public WeComController(WeComCallbackCodec codec,
                            WeComAuthorizationService authorizationService,
                            WeComSendService sendService,
                            WeComStartupGate startupGate) {
        this.codec = codec;
        this.authorizationService = authorizationService;
        this.sendService = sendService;
        this.startupGate = startupGate;
    }

    @PostMapping({
            "/api/wecom/callback",
            "/api/v1/wecom/authorization/callback",
            "/hook_path"
    })
    public ResponseEntity<String> callback(
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestBody String body) {
        try {
            startupGate.requireOpen();
            var decoded = codec.decode(msgSignature, timestamp, nonce, body);
            WeComAuthorizationService.CallbackAck ack = authorizationService.handle(decoded);
            return ack.success() ? ResponseEntity.ok("success")
                    : ResponseEntity.status(503).body("retry");
        } catch (WeComException e) {
            logCallbackRejection(e);
            return ResponseEntity.status(e.httpStatus())
                    .body(e.getMessage());
        }
    }

    @GetMapping({
            "/api/wecom/callback",
            "/api/v1/wecom/authorization/callback",
            "/hook_path"
    })
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
            logCallbackRejection(e);
            return ResponseEntity.status(e.httpStatus()).build();
        }
    }

    @PostMapping("/api/wecom/send")
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

    private static void logCallbackRejection(WeComException exception) {
        if (exception instanceof WeComCallbackFailure callbackFailure) {
            log.warn("event=wecom.callback_rejected stage={} code={} httpStatus={} upstreamPath={} receiveIdSha256={}",
                    callbackFailure.stage().name().toLowerCase(java.util.Locale.ROOT), exception.code(),
                    exception.httpStatus(), exception.upstreamPath(), callbackFailure.receiveIdSha256());
            return;
        }
        log.warn("event=wecom.callback_rejected stage=startup_or_authorization code={} httpStatus={} upstreamPath={}",
                exception.code(), exception.httpStatus(), exception.upstreamPath());
    }
}
