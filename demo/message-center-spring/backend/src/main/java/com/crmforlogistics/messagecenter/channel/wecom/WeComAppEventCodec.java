package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import org.w3c.dom.Document;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 应用级（代开发应用）回调的解码器，首期只处理**客户关系事件**。
 *
 * <p>与 {@link WeComCallbackCodec} 的关系：同协议、不同通道。区别只有两点 ——
 * <ul>
 *   <li>凭据是**应用级** Token / EncodingAESKey（配置在服务商后台的代开发应用详情里），
 *       与模板级那套相互独立；</li>
 *   <li>receiveid 是**密文 corpid**。企业数是增长的，无法预先枚举成集合，所以这里不能沿用
 *       「receiveid ∈ 允许集合」的判定，改成企微协议自带的自洽性检查：
 *       {@code 明文 ToUserName 必须等于密文尾部的 receiveid}。攻击者要伪造它仍须持有 AESKey。</li>
 * </ul>
 *
 * <p>客户联系事件的 XML **没有 {@code InfoType}、也没有 {@code SuiteId}**，这正是不能复用模板
 * 解码器的直接原因：模板路径会先撞 {@code Stage.SUITE_ID}，再撞「未知的 InfoType」。
 *
 * <p><b>失败即拒绝，不做重试</b>：解码失败一律 403。原因是被篡改或算错的请求重试多少次都不会
 * 变对，让企微不停重推只会打满日志。真正该重试的是「解出来了但落库失败」，那条走 503。
 *
 * <p><b>不支持的 changeType 会被 403 丢弃</b>：企微将来新增 changeType 时，必须发版把新值加进
 * {@link #SUPPORTED_CHANGE_TYPES} 才能收。这是刻意的 fail-closed —— 放进来会撞 V81 的字段合同
 * 与 CHECK 约束，失败点反而更深、更晚。
 */
public final class WeComAppEventCodec {
    /** 客户关系事件的事件名；客户群 {@code change_external_chat}、标签 {@code change_external_tag} 不在首期范围。 */
    static final String EVENT_CHANGE_EXTERNAL_CONTACT = "change_external_contact";

    /**
     * 首期支持的 6 个客户关系事件（官方 doc 92130）。
     * 字段核对见 {@code docs/superpowers/specs/2026-09-21-wecom-customer-contact-events-design.md}。
     */
    static final Set<String> SUPPORTED_CHANGE_TYPES = Set.of(
            "add_external_contact",
            "edit_external_contact",
            "add_half_external_contact",
            "del_external_contact",
            "del_follow_user",
            "transfer_fail");

    static final int MAX_FIELD = 512;
    static final int MAX_STATE = 256;
    static final int MAX_WELCOME_CODE = 256;
    static final int MAX_FAIL_REASON = 256;
    static final int MAX_PROVIDER_SOURCE = 32;
    private static final int MAX_EVENT_FIELD = 64;

    private final String token;
    private final byte[] aesKey;
    private final Clock clock;

    public WeComAppEventCodec(AppConfig config) {
        this(config.wecomAppToken(), config.wecomAppEncodingAesKey(), Clock.systemUTC());
    }

    public WeComAppEventCodec(String token, String encodingAesKey) {
        this(token, encodingAesKey, Clock.systemUTC());
    }

    WeComAppEventCodec(String token, String encodingAesKey, Clock clock) {
        this.token = WeComCallbackCipher.require(token, "appToken", MAX_FIELD);
        this.aesKey = WeComCallbackCipher.decodeKey(encodingAesKey);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedAppEvent decode(String msgSignature, String timestamp, String nonce, String encryptXml) {
        validateInput(msgSignature, timestamp, nonce, encryptXml);
        String encrypted = envelope(encryptXml);
        try {
            verifyTimestamp(timestamp);
            WeComCallbackCipher.verifySignature(token, msgSignature, timestamp, nonce, encrypted);
            return parse(WeComCallbackCipher.decrypt(aesKey, encrypted));
        } catch (WeComCallbackFailure exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.PAYLOAD_XML, exception);
        }
    }

    /**
     * URL 有效性验证：解密 {@code echostr} 并原样返回明文。
     *
     * <p>echostr 的明文是企微生成的随机串、不是 XML，所以自洽检查在这里是**有条件**的：
     * 只有当明文确实是含 {@code ToUserName} 的 XML 时才要求它与 receiveid 一致。
     */
    public String verifyAndDecryptEcho(String msgSignature, String timestamp, String nonce,
                                       String encryptedEcho) {
        validateInput(msgSignature, timestamp, nonce, encryptedEcho);
        try {
            verifyTimestamp(timestamp);
            WeComCallbackCipher.verifySignature(token, msgSignature, timestamp, nonce, encryptedEcho);
            WeComCallbackCipher.Decrypted decrypted = WeComCallbackCipher.decrypt(aesKey, encryptedEcho);
            assertSelfConsistent(decrypted);
            return decrypted.xml();
        } catch (WeComCallbackFailure exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.PLAINTEXT, exception);
        }
    }

    private void validateInput(String msgSignature, String timestamp, String nonce, String payload) {
        try {
            WeComCallbackCipher.require(msgSignature, "msg_signature", 128);
            WeComCallbackCipher.require(timestamp, "timestamp", 32);
            WeComCallbackCipher.require(nonce, "nonce", 128);
            WeComCallbackCipher.require(payload, "encrypt", WeComCallbackCipher.MAX_XML_BYTES);
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.INPUT, exception);
        }
    }

    private static String envelope(String encryptXml) {
        try {
            return WeComCallbackCipher.encryptedValue(encryptXml);
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.ENVELOPE_XML, exception);
        }
    }

    private void verifyTimestamp(String timestamp) {
        try {
            long epoch = Long.parseLong(timestamp);
            if (Math.abs(clock.instant().getEpochSecond() - epoch) > 900) {
                throw new SecurityException("callback timestamp is outside the accepted window");
            }
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.TIMESTAMP, exception);
        }
    }

    private DecodedAppEvent parse(WeComCallbackCipher.Decrypted decrypted) {
        Document document;
        try {
            if (decrypted.xml().getBytes(StandardCharsets.UTF_8).length > WeComCallbackCipher.MAX_XML_BYTES) {
                throw new IllegalArgumentException("XML too large");
            }
            document = WeComCallbackCipher.parseXml(decrypted.xml());
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.PAYLOAD_XML, exception);
        }
        String toUserName = WeComCallbackCipher.field(document, "ToUserName");
        requireSelfConsistent(toUserName, decrypted.receiveId());
        try {
            String fromUserName = WeComCallbackCipher.field(document, "FromUserName");
            String msgType = WeComCallbackCipher.field(document, "MsgType");
            if (!"event".equals(msgType)) {
                // 本能力不处理消息类回调；会话真源仍是 chatdata 会话存档。
                throw unsupported("MsgType is not event");
            }
            String event = WeComCallbackCipher.field(document, "Event");
            if (!EVENT_CHANGE_EXTERNAL_CONTACT.equals(event)) {
                throw unsupported("event is not " + EVENT_CHANGE_EXTERNAL_CONTACT);
            }
            String changeType = bounded(WeComCallbackCipher.field(document, "ChangeType"),
                    "ChangeType", MAX_EVENT_FIELD);
            if (changeType == null) {
                throw failure(WeComCallbackFailure.Stage.APP_EVENT_MISSING_CHANGE_TYPE,
                        new IllegalArgumentException("ChangeType is required"));
            }
            if (!SUPPORTED_CHANGE_TYPES.contains(changeType)) {
                throw unsupported("change type is not supported");
            }
            String userId = bounded(WeComCallbackCipher.field(document, "UserID"), "UserID", MAX_FIELD);
            String externalUserId = bounded(WeComCallbackCipher.field(document, "ExternalUserID"),
                    "ExternalUserID", MAX_FIELD);
            if (userId == null || externalUserId == null) {
                // 6 个客户关系事件都带这两个字段（官方 doc 92130 逐个核对），缺任一即不是本能力认识的负载。
                throw new IllegalArgumentException("UserID and ExternalUserID are required");
            }
            return new DecodedAppEvent(
                    toUserName,
                    bounded(fromUserName, "FromUserName", MAX_EVENT_FIELD),
                    event,
                    changeType,
                    userId,
                    externalUserId,
                    bounded(WeComCallbackCipher.field(document, "ChatId"), "ChatId", MAX_FIELD),
                    bounded(WeComCallbackCipher.field(document, "State"), "State", MAX_STATE),
                    bounded(WeComCallbackCipher.field(document, "WelcomeCode"), "WelcomeCode", MAX_WELCOME_CODE),
                    bounded(WeComCallbackCipher.field(document, "FailReason"), "FailReason", MAX_FAIL_REASON),
                    bounded(WeComCallbackCipher.field(document, "Source"), "Source", MAX_PROVIDER_SOURCE),
                    providerCreatedAt(document));
        } catch (WeComCallbackFailure exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(WeComCallbackFailure.Stage.PAYLOAD_XML, exception);
        }
    }

    /**
     * {@code CreateTime} 必须可解析：它进去重键（企微重推时该值不变，而本地接收时刻每次都变），
     * 拿不到就等于同一事件会被重复记两次。
     */
    private static Instant providerCreatedAt(Document document) {
        String createTime = WeComCallbackCipher.field(document, "CreateTime");
        long epoch;
        try {
            epoch = Long.parseLong(createTime);
            return Instant.ofEpochSecond(epoch);
        } catch (NumberFormatException | DateTimeException exception) {
            throw failure(WeComCallbackFailure.Stage.PAYLOAD_XML,
                    new IllegalArgumentException("CreateTime is required"));
        }
    }

    private static void requireSelfConsistent(String toUserName, String receiveId) {
        if (toUserName.isBlank() || !toUserName.equals(receiveId)) {
            throw new WeComCallbackFailure(WeComCallbackFailure.Stage.RECEIVE_ID,
                    new SecurityException("app callback receive id is not self-consistent"), receiveId);
        }
    }

    private static void assertSelfConsistent(WeComCallbackCipher.Decrypted decrypted) {
        if (!decrypted.xml().trim().startsWith("<")) {
            return;
        }
        String toUserName;
        try {
            toUserName = WeComCallbackCipher.field(
                    WeComCallbackCipher.parseXml(decrypted.xml()), "ToUserName");
        } catch (Exception exception) {
            return;
        }
        if (!toUserName.isBlank() && !toUserName.equals(decrypted.receiveId())) {
            throw new WeComCallbackFailure(WeComCallbackFailure.Stage.RECEIVE_ID,
                    new SecurityException("app callback receive id is not self-consistent"),
                    decrypted.receiveId());
        }
    }

    /** 空白一律归一到 {@code null}，去重键才不会把 {@code ""} 与 {@code null} 当成两个事件。 */
    private static String bounded(String value, String name, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(name + " must not exceed " + max + " characters");
        }
        return value;
    }

    private static WeComCallbackFailure unsupported(String reason) {
        return new WeComCallbackFailure(WeComCallbackFailure.Stage.APP_EVENT_UNSUPPORTED,
                new IllegalArgumentException(reason));
    }

    private static WeComCallbackFailure failure(WeComCallbackFailure.Stage stage, Throwable cause) {
        return new WeComCallbackFailure(stage, cause);
    }

    /**
     * 解码结果。
     *
     * @param toUserName        密文 corpid，等于解密得到的 receiveid
     * @param fromUserName      系统事件固定为 {@code sys}
     * @param userId            企业服务人员的密文 userid
     * @param externalUserId    外部联系人 userid，注意不是企业成员账号
     * @param state             渠道标识（「联系我」配置的 state 或获客链接的 customer_channel）
     * @param welcomeCode       欢迎语 code；外部联系人已开始聊天时企微不返回该字段
     * @param failReason        仅 {@code transfer_fail}：{@code customer_refused} / {@code customer_limit_exceed}
     * @param providerSource    仅 {@code del_external_contact}：{@code DELETE_BY_TRANSFER} 表示因在职继承自动删除
     * @param providerCreatedAt 事件 XML 的 {@code CreateTime}
     */
    public record DecodedAppEvent(
            String toUserName,
            String fromUserName,
            String event,
            String changeType,
            String userId,
            String externalUserId,
            String chatId,
            String state,
            String welcomeCode,
            String failReason,
            String providerSource,
            Instant providerCreatedAt) {}
}
