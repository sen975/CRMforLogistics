package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.channel.email.EmailException;
import com.crmforlogistics.messagecenter.channel.email.EmailSendService;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageApplicationService;
import com.crmforlogistics.messagecenter.service.chatapp.outbox.MessageSendApplicationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「给某个联系人发一条消息」——按渠道解析收件人，然后交给该渠道真实的发送链路。
 *
 * <h2>它解决的唯一新问题：收件人从哪来</h2>
 * 两个渠道各自的发送入口早就存在（{@code EmailSendService.send(ownerId, to, …)} 真走 SMTP；
 * {@code ChatAppMessageApplicationService.acceptContactIdentity(…)} 真入出站队列），
 * 界面上的调用方是<b>人已经填好了地址</b>（{@code MessageController.sendEmail} 直接收 {@code to}）。
 * 助手这一侧没有「人填地址」这一步，而<b>让模型给出地址是最坏的一种做法</b> ——
 * 模型生成一个看起来很像的字符串（{@code wang@example.com}），发出去就收不回来。
 *
 * <p>所以这里的做法是：调用方只给「哪个联系人 + 哪个渠道」，地址由本类按
 * {@code contact_identities} 解析。<b>整条路上没有任何一个参数由模型决定收件地址。</b>
 *
 * <h2>归属防线在 SQL 里，不在这里</h2>
 * {@link ContactIdentityMapper#findByContactIdAndOwner(UUID, UUID)} 的 {@code where} 里已经带了
 * 归属/授权/软删/合并态四道条件（{@code created_by} 或会话授权或管理员）。本类只按渠道挑一条、
 * 取地址，<b>不重复也不放宽</b>任何一条。传 {@code userId} 而不是「先解析再校验」，就是为了
 * 让「越权」这件事根本没有表达的地方。
 *
 * <h2>「哪一条地址」由既有 SQL 的顺序决定</h2>
 * 一个联系人可能有多个同渠道身份（两个邮箱、两个号码），而 SQL 已经按
 * {@code is_primary desc, created_at, id} 排好序。这里<b>取第一条</b>，不重新排序 ——
 * 换一套排序就是在这里偷偷定义「主地址」，而界面上显示的主地址用的是另一套规则。
 * 卡片会把实际地址显示出来供人核对，这就是这套取舍的安全网。
 *
 * <h2>地址与卡片同源</h2>
 * {@link #resolve} 是卡片显示地址的<b>唯一</b>来源，发送走的也是它。
 * 所以「卡片上写的」与「实际发往的」不是两段各自算出来的字符串，而是同一个表达式的结果 ——
 * 「显示一个地址、发给另一个地址」这类错在这里没有发生的余地。
 * （{@link #CHANNEL_CHATAPP} 的地址表达式与 {@code ChatAppMessageApplicationService.recipientForAccount}
 * 一致，都是 {@link ContactPointUtil#normalizePhone}。）
 */
@Service
public class OutboundMessageService {

    public static final String CHANNEL_EMAIL = "email";
    public static final String CHANNEL_CHATAPP = "chatapp";

    private final ContactIdentityMapper identities;
    private final EmailSendService emails;
    private final ChatAppMessageApplicationService chatApp;

    public OutboundMessageService(ContactIdentityMapper identities, EmailSendService emails,
                                  ChatAppMessageApplicationService chatApp) {
        this.identities = identities;
        this.emails = emails;
        this.chatApp = chatApp;
    }

    /**
     * 一次调用的收件人。
     *
     * @param identityId 身份行 id。chatapp 那条路要用它（渠道自己会再校验一遍这个身份归不归你）
     * @param address    实际用于发送的地址：邮箱是规范化后的邮箱，chatapp 是去掉分隔符的手机号
     * @param channelType 与调用方传入的一致，回带出来免得调用方再拼一次
     */
    public record Recipient(UUID identityId, String address, String channelType) {
    }

    /**
     * 一封邮件发出后的回执。
     *
     * <p>刻意<b>不</b>把 {@code EmailSendService.SendResult} 直接当返回类型暴露出去 —— 那是渠道包的类型，
     * 业务域与助手侧都不该 import 它（架构门禁 {@code ArchitectureBoundaryTest} 会拒）。
     * 渠道换实现时调用方一行都不用改，这正是这一层存在的理由。
     */
    public record EmailReceipt(String messageId, String from, String to, String subject, String status) {
        static EmailReceipt of(EmailSendService.SendResult result) {
            return new EmailReceipt(result.messageId(), result.from(), result.to(),
                    result.subject(), result.status());
        }
    }

    /**
     * 一条 WhatsApp 消息入队后的回执。
     *
     * <p>{@code status} 当前恒为 {@code pending}：只是入队，投递由 worker 完成。
     * 调用方回话时必须照这个事实说，不能写成「已送达」。
     */
    public record ChatAppReceipt(UUID messageId, String status, boolean duplicate) {
        static ChatAppReceipt of(MessageSendApplicationService.MessageAccepted accepted) {
            return new ChatAppReceipt(accepted.messageId(), accepted.status(), accepted.duplicate());
        }
    }

    /**
     * 解析收件人。拿不到返回 {@code null}（而不是抛异常）：卡片那一侧需要「拿不到」这个值
     * 来把话说清楚，而不是让整轮提问失败。
     *
     * <p>跳过没有该渠道身份、以及地址为空白/归零后为空的身份 ——
     * 一条「有身份但地址取不出可用值」的记录不该把这次发送变成一个发给空串的 SMTP 调用。
     */
    public Recipient resolve(UUID userId, UUID contactId, String channelType) {
        if (userId == null || contactId == null || channelType == null || channelType.isBlank()) {
            return null;
        }
        List<ContactIdentityEntity> candidates = identities.findByContactIdAndOwner(contactId, userId);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (ContactIdentityEntity identity : candidates) {
            if (!channelType.equalsIgnoreCase(identity.getChannelType())) {
                continue;
            }
            String address = addressOf(identity, channelType);
            if (!address.isBlank()) {
                return new Recipient(identity.getId(), address, channelType);
            }
        }
        return null;
    }

    /**
     * 发一封邮件。{@code to} <b>不是参数</b> —— 它由 {@link #resolve} 从联系人档案里取。
     *
     * <p>{@code clientRequestId} 在这条路上没有对应概念（{@code EmailSendService} 自己生成
     * Message-ID 并落 {@code email_submissions}），所以不做幂等键；「只发一次」由上游的
     * 待确认动作抢占保证。
     */
    public EmailReceipt sendEmail(UUID userId, UUID contactId, String subject, String body) {
        Recipient recipient = require(userId, contactId, CHANNEL_EMAIL);
        try {
            return EmailReceipt.of(emails.send(userId, recipient.address(), subject, body));
        } catch (EmailException e) {
            throw translate(e);
        } catch (Exception e) {
            // send 声明了 checked Exception（MessagingException 等）。工具层没有受检异常的出口，
            // 在这里收成一个码，别把「一句人话」的任务丢给上层去 catch(Exception)。
            throw new OutboundException(OutboundException.SEND_FAILED, "邮件发送失败", e);
        }
    }

    /**
     * 通过 WhatsApp 渠道发一条文本消息。
     *
     * <p>{@code clientRequestId} 由本类生成，<b>刻意不做成参数</b>：它是出站侧的幂等键
     * （{@code MessageSendApplicationService} 按 {@code (channelAccountId, clientRequestId)} 去重）。
     * 让模型决定它，模型就能（哪怕只是凑巧）交出一个已用过的值，而那条路的去重是<b>静默成功</b>的 ——
     * 调用方会收到一个看起来正常的 {@code duplicate=true}，消息却一条都没发出去。
     *
     * <p>返回的 {@code status} 是 {@code pending}：消息<b>只是入了出站队列</b>，
     * 真正投递由 worker 完成。调用方回话时必须照这个事实说，不能写成「已送达」。
     */
    public ChatAppReceipt sendChatApp(UUID userId, UUID contactId, String text) {
        Recipient recipient = require(userId, contactId, CHANNEL_CHATAPP);
        try {
            return ChatAppReceipt.of(chatApp.acceptContactIdentity(contactId, recipient.identityId(), "text",
                    UUID.randomUUID().toString(), Map.of("text", text), userId));
        } catch (RuntimeException e) {
            // 收件人已经在上面解析过了，所以到这里还剩的失败都在账号/授权/装配那一侧：
            // 账号停用、账号不在你名下、这个联系人不可通过该渠道发送、渠道未启用。
            // 它们对用户的共同意思是「现在这条路走不通，换参数没用」—— 所以不细分（理由见 OutboundException）。
            throw new OutboundException(OutboundException.CHANNEL_UNAVAILABLE,
                    "WhatsApp 渠道当前不可用", e);
        }
    }

    /** 渠道标签，只用于服务端日志与异常描述（给用户看的话术在调用方那一层）。 */
    public static String channelLabel(String channelType) {
        return CHANNEL_CHATAPP.equalsIgnoreCase(channelType) ? "WhatsApp" : "邮箱";
    }

    // ---------- 内部 ----------

    private Recipient require(UUID userId, UUID contactId, String channelType) {
        Recipient recipient = resolve(userId, contactId, channelType);
        if (recipient == null) {
            throw new OutboundException(OutboundException.RECIPIENT_MISSING,
                    "联系人在 " + channelLabel(channelType) + " 渠道上没有可用的收件地址");
        }
        return recipient;
    }

    /**
     * 身份行 → 实际发送地址。
     *
     * <p>邮箱优先取 {@code normalized_value}（{@code EmailSendService} 写身份时正是用它做归一化键，
     * 所以两者同源），退回 {@code identity_value} 再抽一次 {@code @} 之间的部分 ——
     * 身份值可能是 {@code "王工 <wang@x.com>"} 这种带显示名的形态。
     */
    private static String addressOf(ContactIdentityEntity identity, String channelType) {
        if (CHANNEL_EMAIL.equalsIgnoreCase(channelType)) {
            String normalized = identity.getNormalizedValue();
            if (normalized != null && !normalized.isBlank()) {
                return normalized.strip();
            }
            return ContactPointUtil.extractEmail(identity.getIdentityValue());
        }
        return ContactPointUtil.normalizePhone(identity.getIdentityValue());
    }

    /**
     * 底层邮件错误 → 本类词表。
     *
     * <p>按 {@code code()} 分派（那是 {@code EmailException} 公开的契约），不嗅探 {@code getMessage()}。
     * 未知的码落到 {@code SEND_FAILED} 而不是猜一个更"具体"的 —— 猜错的码会让用户去改一个
     * 本来就对的东西（例如把「凭据解不开」报成「地址不对」）。
     */
    private static OutboundException translate(EmailException e) {
        String code = e.code() == null ? "" : e.code();
        return switch (code) {
            case "EMAIL_RECIPIENT_NOT_FOUND" -> new OutboundException("EMAIL_RECIPIENT_NOT_FOUND",
                    "收件邮箱不属于当前用户的已有联系人", e);
            case "EMAIL_SEND_OUTCOME_UNKNOWN" -> new OutboundException(OutboundException.OUTCOME_UNKNOWN,
                    "SMTP 已接收，但本地投递状态没能记全；重发前必须先确认", e);
            case "CHANNEL_ACCOUNT_REQUIRED", "CHANNEL_ACCOUNT_CREDENTIALS_UNAVAILABLE",
                 "AUTHENTICATION_REQUIRED" -> new OutboundException(OutboundException.CHANNEL_UNAVAILABLE,
                    "发件邮箱账号不可用", e);
            default -> new OutboundException(OutboundException.SEND_FAILED, "邮件发送失败", e);
        };
    }
}
