package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import org.w3c.dom.Document;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模板级（suite）授权回调的解码器。
 *
 * <p>receiveid 语义是 {@code suite_id}：{@code suite_id} 在部署时就是已知的、可枚举的，
 * 所以这里用「receiveid 必须落在允许集合内」来校验。加密与签名细节已抽到
 * {@link WeComCallbackCipher}，本类只保留「模板通道特有的取舍」。
 *
 * <p><b>本类行为刻意保持不变</b>：{@code suite_ticket} 每 10 分钟推一次，丢了会影响所有企业的
 * access_token，属生产关键路径。客户联系事件（应用级通道）因此新增平行的
 * {@link WeComAppEventCodec}，而不是在这里加分支。
 */
public final class WeComCallbackCodec {
    private static final int MAX_FIELD = 512;
    private final String suiteId;
    private final Set<String> allowedSuiteIds;
    private final String callbackReceiveId;
    private final Set<String> allowedEchoReceiveIds;
    private final String token;
    private final byte[] aesKey;
    private final Clock clock;

    public WeComCallbackCodec(AppConfig config) {
        this(config.wecomSuiteId(), "",
                !config.wecomCallbackReceiveId().isBlank() ? config.wecomCallbackReceiveId() : config.wecomSuiteId(),
                config.wecomToken(), config.wecomEncodingAesKey(), Clock.systemUTC());
    }

    public WeComCallbackCodec(String suiteId, String token, String encodingAesKey) {
        this(suiteId, token, encodingAesKey, Clock.systemUTC());
    }

    WeComCallbackCodec(String suiteId, String token, String encodingAesKey, Clock clock) {
        this(suiteId, suiteId, token, encodingAesKey, clock);
    }

    WeComCallbackCodec(String suiteId, String callbackReceiveId, String token,
                       String encodingAesKey, Clock clock) {
        this(suiteId, "", callbackReceiveId, token, encodingAesKey, clock);
    }

    private WeComCallbackCodec(String suiteId, String loginSuiteId, String callbackReceiveId, String token,
                               String encodingAesKey, Clock clock) {
        this.suiteId = WeComCallbackCipher.require(suiteId, "suiteId", 128);
        LinkedHashSet<String> suiteIds = new LinkedHashSet<>();
        suiteIds.add(this.suiteId);
        if (loginSuiteId != null && !loginSuiteId.isBlank()) {
            suiteIds.add(WeComCallbackCipher.require(loginSuiteId, "loginSuiteId", 128));
        }
        this.allowedSuiteIds = Set.copyOf(suiteIds);
        this.callbackReceiveId = WeComCallbackCipher.require(callbackReceiveId, "callbackReceiveId", 128);
        LinkedHashSet<String> echoReceiveIds = new LinkedHashSet<>(suiteIds);
        echoReceiveIds.add(this.callbackReceiveId);
        this.allowedEchoReceiveIds = Set.copyOf(echoReceiveIds);
        this.token = WeComCallbackCipher.require(token, "token", 512);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.aesKey = WeComCallbackCipher.decodeKey(encodingAesKey);
    }

    public DecodedCallback decode(String msgSignature, String timestamp, String nonce, String encryptXml)
            throws WeComException {
        try {
            WeComCallbackCipher.require(msgSignature, "msg_signature", 128);
            WeComCallbackCipher.require(timestamp, "timestamp", 32);
            WeComCallbackCipher.require(nonce, "nonce", 128);
            WeComCallbackCipher.require(encryptXml, "encrypt", WeComCallbackCipher.MAX_XML_BYTES);
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.INPUT, exception);
        }
        String encrypted;
        try {
            encrypted = WeComCallbackCipher.encryptedValue(encryptXml);
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.ENVELOPE_XML, exception);
        }
        try {
            long epoch = verifyTimestamp(timestamp);
            WeComCallbackCipher.verifySignature(token, msgSignature, timestamp, nonce, encrypted);
            WeComCallbackCipher.Decrypted payload = WeComCallbackCipher.decrypt(aesKey, encrypted);
            requireReceiveId(payload.receiveId(), allowedSuiteIds);
            return parse(payload.xml(), payload.receiveId(), epoch);
        } catch (WeComCallbackFailure exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.PAYLOAD_XML, exception);
        }
    }

    public String verifyAndDecryptEcho(String msgSignature, String timestamp, String nonce, String encryptedEcho)
            throws WeComException {
        try {
            WeComCallbackCipher.require(msgSignature, "msg_signature", 128);
            WeComCallbackCipher.require(timestamp, "timestamp", 32);
            WeComCallbackCipher.require(nonce, "nonce", 128);
            WeComCallbackCipher.require(encryptedEcho, "echostr", WeComCallbackCipher.MAX_XML_BYTES);
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.INPUT, exception);
        }
        try {
            verifyTimestamp(timestamp);
            WeComCallbackCipher.verifySignature(token, msgSignature, timestamp, nonce, encryptedEcho);
            WeComCallbackCipher.Decrypted decrypted = WeComCallbackCipher.decrypt(aesKey, encryptedEcho);
            requireReceiveId(decrypted.receiveId(), allowedEchoReceiveIds);
            return decrypted.xml();
        } catch (WeComCallbackFailure exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.PLAINTEXT, exception);
        }
    }

    /** receiveid 不在允许集合内即拒绝；带上 receiveid 让日志能输出脱敏摘要。 */
    private static void requireReceiveId(String receiveId, Set<String> allowed) {
        if (!allowed.contains(receiveId)) {
            throw new WeComCallbackFailure(WeComCallbackFailure.Stage.RECEIVE_ID,
                    new SecurityException("callback receive id is invalid"), receiveId);
        }
    }

    private long verifyTimestamp(String timestamp) {
        try {
            long epoch = Long.parseLong(timestamp);
            if (Math.abs(clock.instant().getEpochSecond() - epoch) > 900) {
                throw new SecurityException("callback timestamp is outside the accepted window");
            }
            return epoch;
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.TIMESTAMP, exception);
        }
    }

    private DecodedCallback parse(String xml, String receiveId, long epoch) throws Exception {
        Document document;
        try {
            if (xml.getBytes(StandardCharsets.UTF_8).length > WeComCallbackCipher.MAX_XML_BYTES) {
                throw new IllegalArgumentException("XML too large");
            }
            document = WeComCallbackCipher.parseXml(xml);
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.PAYLOAD_XML, exception);
        }
        String callbackSuiteId = WeComCallbackCipher.field(document, "SuiteId");
        if (callbackSuiteId.isBlank() || !allowedSuiteIds.contains(callbackSuiteId)
                || !callbackSuiteId.equals(receiveId)) {
            throw failure(WeComCallbackFailure.Stage.SUITE_ID,
                    new SecurityException("callback suite id is invalid"));
        }
        try {
            String infoType = WeComCallbackCipher.require(
                    WeComCallbackCipher.field(document, "InfoType"), "InfoType", 64);
            String authCorpId = WeComCallbackCipher.field(document, "AuthCorpId");
            String authCode = WeComCallbackCipher.field(document, "AuthCode");
            String suiteTicket = WeComCallbackCipher.field(document, "SuiteTicket");
            String state = WeComCallbackCipher.field(document, "State");
            for (String value : List.of(authCorpId, authCode, suiteTicket, state)) {
                if (value.length() > MAX_FIELD) {
                    throw new IllegalArgumentException("callback field is too long");
                }
            }
            return new DecodedCallback(callbackSuiteId, infoType, authCorpId, authCode, suiteTicket, state,
                    Instant.ofEpochSecond(epoch));
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.PAYLOAD_XML, exception);
        }
    }

    private static WeComCallbackFailure failure(WeComCallbackFailure.Stage stage, Throwable cause) {
        return new WeComCallbackFailure(stage, cause);
    }

    public static String sha1(String token, String timestamp, String nonce, String encrypted) {
        return WeComCallbackCipher.sha1(token, timestamp, nonce, encrypted);
    }

    public record DecodedCallback(String suiteId, String infoType, String authCorpId, String authCode,
                                   String suiteTicket, String state, Instant timestamp) {}
}
