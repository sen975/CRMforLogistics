package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.service.wecom.WeComAuthorizationService;
import com.crmforlogistics.messagecenter.service.wecom.WeComContactEventService;
import com.crmforlogistics.messagecenter.service.wecom.WeComStartupGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    /**
     * 应用级回调解码器按配置注册：凭据没配时这个 bean 不存在，端点返回 503 而不是启动失败。
     * 用 {@link ObjectProvider} 而不是 {@code @Nullable} 注入，是为了不在别处引入「可能为 null」的假象。
     */
    private final ObjectProvider<WeComAppEventCodec> appEventCodec;
    private final WeComContactEventService contactEventService;

    public WeComController(WeComCallbackCodec codec,
                            WeComAuthorizationService authorizationService,
                            WeComSendService sendService,
                            WeComStartupGate startupGate,
                            ObjectProvider<WeComAppEventCodec> appEventCodec,
                            WeComContactEventService contactEventService) {
        this.codec = codec;
        this.authorizationService = authorizationService;
        this.sendService = sendService;
        this.startupGate = startupGate;
        this.appEventCodec = appEventCodec;
        this.contactEventService = contactEventService;
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

    /**
     * 应用级（客户联系）事件回调。
     *
     * <p>与模板回调 {@code /api/wecom/callback} **并列**而不是共用：两条通道的凭据、receiveid
     * 语义、失败处置都不同，混在一个入口里做二次解密分流会让授权链路的解析分支复杂化，而授权链路
     * 是生产关键路径。前提是企微允许为两个配置填不同 URL（阶段 0.1 已确认）。
     *
     * <p>回包语义：解码失败 → 403（请求本身不对，重试无意义）；服务未就绪 / 功能关闭 /
     * 未知安装 / 数据库不可用 → 503（让企微重推）；落库提交成功或幂等冲突 → 200 "success"。
     */
    @PostMapping("/api/v1/wecom/app-callback")
    public ResponseEntity<String> appCallback(
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            HttpServletRequest request) {
        WeComAppEventCodec appCodec = appEventCodec.getIfAvailable();
        if (appCodec == null) {
            log.warn("event=wecom.app_callback_rejected reason=codec_not_configured");
            return ResponseEntity.status(503).body("retry");
        }
        try {
            String body = readBoundedAppCallbackBody(request);
            startupGate.requireOpen();
            WeComAppEventCodec.DecodedAppEvent decoded =
                    appCodec.decode(msgSignature, timestamp, nonce, body);
            WeComContactEventService.IngestResult result = contactEventService.ingest(decoded);
            if (result.acked()) {
                return ResponseEntity.ok("success");
            }
            log.warn("event=wecom.app_callback_retry reason={} receiveIdSha256={}",
                    result, WeComCallbackCipher.sha256Hex(decoded.toUserName()));
            return ResponseEntity.status(503).body("retry");
        } catch (WeComException e) {
            logCallbackRejection(e);
            return ResponseEntity.status(e.httpStatus()).body(e.getMessage());
        } catch (IOException e) {
            log.warn("event=wecom.app_callback_rejected reason=body_read_failed type={}",
                    e.getClass().getSimpleName());
            return ResponseEntity.status(503).body("retry");
        }
    }

    static String readBoundedAppCallbackBody(HttpServletRequest request) throws IOException {
        int maxBytes = WeComCallbackCipher.MAX_XML_BYTES;
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBytes) {
            throw new WeComException("WECOM_CALLBACK_BODY_TOO_LARGE", 413,
                    "企业微信回调请求体超过大小限制");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = request.getInputStream().read(buffer, 0,
                Math.min(buffer.length, maxBytes - total + 1))) != -1) {
            if (read > maxBytes - total) {
                throw new WeComException("WECOM_CALLBACK_BODY_TOO_LARGE", 413,
                        "企业微信回调请求体超过大小限制");
            }
            body.write(buffer, 0, read);
            total += read;
        }
        return body.toString(StandardCharsets.UTF_8);
    }

    /** 应用级回调的 URL 有效性验证：验签、解密 echostr、回明文。 */
    @GetMapping("/api/v1/wecom/app-callback")
    public ResponseEntity<String> verifyAppCallback(
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestParam("echostr") String echostr) {
        WeComAppEventCodec appCodec = appEventCodec.getIfAvailable();
        if (appCodec == null) {
            log.warn("event=wecom.app_callback_rejected reason=codec_not_configured");
            return ResponseEntity.status(503).build();
        }
        try {
            startupGate.requireOpen();
            return ResponseEntity.ok(appCodec.verifyAndDecryptEcho(
                    msgSignature, timestamp, nonce, echostr));
        } catch (WeComException e) {
            logCallbackRejection(e);
            return ResponseEntity.status(e.httpStatus()).build();
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
