package com.crmforlogistics.messagecenter.service.channel;

/**
 * 出站发送失败的词汇表。
 *
 * <h2>为什么不直接抛底层异常</h2>
 * 这条路上的失败来自三个不同的世界：SMTP（{@code EmailException} 的 {@code EMAIL_*}）、
 * WhatsApp 渠道（{@code IllegalArgumentException} 的 {@code CHATAPP_*}、以及 {@code SecurityException}）、
 * 还有联系人档案本身（没有可用地址）。调用方（助手工具）需要回答的是
 * <b>「这句话该怎么对用户说、要不要让模型换个参数重试」</b>，而不是底层哪个组件出的错。
 *
 * <p>所以先把它们收敛成四个码，再由调用方翻译成用户可见的话。词表放在服务层、话术放在
 * 调用方，是因为同一件事在不同入口上的说法本就不同（界面上可以直接红字提示，助手里要
 * 让模型知道"别再换个说法试了"）。
 *
 * <h2>为什么刻意不分得更细</h2>
 * chatapp 那条路上，{@code CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE} /
 * {@code CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND} / {@code CHATAPP_CONVERSATION_FORBIDDEN}
 * 都是「这次发不出去」，而它们的区别对用户毫无意义（都不能靠改参数解决）。
 * 按错误码字符串去嗅探细分，等于把别人的错误码词表变成一份隐式契约 ——
 * 那种耦合在对方加一个码时不会报错，只会静默走错分支。所以这里只按
 * <b>能改变用户行为</b>的差异切分：没有地址 / 渠道不可用 / 结果未知 / 其他。
 */
public class OutboundException extends RuntimeException {

    /**
     * 这个联系人在该渠道上没有可用的收件地址。
     *
     * <p>刻意<b>不</b>区分「他档案里没这个渠道」与「他不在你的范围内」：区分开就等于告诉
     * 另一个账号「这个人存在」。对用户的指引也一样 —— 都该去通讯录里确认一下。
     */
    public static final String RECIPIENT_MISSING = "OUTBOUND_RECIPIENT_MISSING";

    /** 渠道本身现在用不了：账号没配、凭据解不开、账号不在你名下或已停用。改参数无济于事。 */
    public static final String CHANNEL_UNAVAILABLE = "OUTBOUND_CHANNEL_UNAVAILABLE";

    /**
     * <b>可能已经发出去了</b>：SMTP 收到了，但对端结果没能落库
     * （{@code EmailSendService} 的 {@code EMAIL_SEND_OUTCOME_UNKNOWN}）。
     *
     * <p>单独一个码是因为它是唯一一个「重试会真的造成第二次投递」的失败。
     * 对用户的话术必须是「先去确认，别直接重发」，而不是「失败，请重试」。
     */
    public static final String OUTCOME_UNKNOWN = "OUTBOUND_OUTCOME_UNKNOWN";

    /** 其余失败。未预期的异常由调用方兜成内部错误。 */
    public static final String SEND_FAILED = "OUTBOUND_SEND_FAILED";

    private final String code;

    public OutboundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public OutboundException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
