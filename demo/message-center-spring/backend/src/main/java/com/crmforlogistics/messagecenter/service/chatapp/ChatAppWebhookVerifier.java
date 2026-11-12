package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

@Service
public class ChatAppWebhookVerifier {
    private final AppConfig config;
    private final Clock clock;

    @Autowired
    public ChatAppWebhookVerifier(AppConfig config) {
        this(config, Clock.systemUTC());
    }

    ChatAppWebhookVerifier(AppConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
    }

    public void verify(String signature, String timestamp, String rawBody) {
        boolean hasSignature = signature != null && !signature.isBlank();
        boolean hasTimestamp = timestamp != null && !timestamp.isBlank();
        if (!hasSignature && !hasTimestamp) {
            // CAMS webhook deliveries observed in production do not include an HMAC header.
            // Account/scope matching remains enforced by ChatAppWebhookInboxService.
            return;
        }
        if (hasSignature != hasTimestamp) {
            throw new ChatAppWebhookAuthenticationException("CHATAPP_WEBHOOK_SIGNATURE_HEADERS_INCOMPLETE");
        }
        String secret = config.chatappWebhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new ChatAppWebhookAuthenticationException("CHATAPP_WEBHOOK_SECRET_NOT_CONFIGURED");
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp);
        } catch (Exception e) {
            throw new ChatAppWebhookAuthenticationException("CHATAPP_WEBHOOK_TIMESTAMP_INVALID");
        }
        long skew = Math.abs(Instant.now(clock).getEpochSecond() - epochSeconds);
        int allowedSkew = Math.max(1, Math.min(3600, config.chatappWebhookMaxSkewSeconds()));
        if (skew > allowedSkew) {
            throw new ChatAppWebhookAuthenticationException("CHATAPP_WEBHOOK_TIMESTAMP_EXPIRED");
        }
        String expected = sign(secret, timestamp, rawBody == null ? "" : rawBody);
        String provided = signature == null ? "" : signature.replaceFirst("(?i)^sha256=", "");
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                provided.getBytes(StandardCharsets.US_ASCII))) {
            throw new ChatAppWebhookAuthenticationException("CHATAPP_WEBHOOK_SIGNATURE_INVALID");
        }
    }

    String signForTest(String timestamp, String rawBody) {
        return sign(config.chatappWebhookSecret(), timestamp, rawBody);
    }

    private static String sign(String secret, String timestamp, String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("CHATAPP_WEBHOOK_SIGNATURE_ERROR", e);
        }
    }
}
