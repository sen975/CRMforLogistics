package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComSendService;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 「把一条文本推给我自己的企业微信」—— 助手侧唯一的入口。
 *
 * <h2>为什么需要这一层，而不是让工具直接调 {@code WeComSendService}</h2>
 * 因为<b>架构门禁不允许</b>：{@code ArchitectureBoundaryTest} 规定
 * {@code service..} 不得依赖顶层 {@code channel..}，白名单只有 {@code service.channel}、
 * {@code service.wecom}、{@code service.chatapp} 三个「职责本身就是协调渠道」的包，而
 * {@code service.assistant.mcp} 不在其中。发送实现 {@link WeComSendService} 住在
 * {@code channel.wecom}，所以工具与它之间必须有一层住在白名单里的门面 —— 这一层。
 *
 * <p>门禁注释明确写了「不要为了绕过门禁而加白名单」，所以这里不是加白名单，是<b>搬层</b>。
 * 同样的形状在企微域已经有一处先例：{@code WeComSummaryReadService} 就是被
 * {@code WeComAssistantTools} 这样依赖的。
 *
 * <h2>这一层做的唯一一件正事：把 {@code WeComException} 翻译成不依赖 channel 包的语义</h2>
 * 工具层<b>连 {@code WeComException} 这个类型都不能 import</b>（它也在 {@code channel.wecom} 里），
 * 所以异常的翻译必须发生在这里。翻译的结果是三档，它们的处置<b>完全不同</b>：
 *
 * <ol>
 *   <li><b>不可用</b> → {@link IllegalStateException}（与 {@code WeComSummaryReadService}
 *       同一口径）。指引是「去绑定 / 找管理员」，换什么参数都一样，重试也没意义。</li>
 *   <li><b>被上游明确拒绝</b> → {@link PushRejected}。企微回了非零 {@code errcode}，
 *       消息<b>确定没有送达</b>，处置是「看看哪里不对再试」。</li>
 *   <li><b>结果未知</b> → {@link OutcomeUnknown}。这是唯一一个「重试 = 第二条真实消息」的失败，
 *       必须单独一个类型，否则模型看到笼统的失败就会自己重试，用户收到两条一样的推送。</li>
 * </ol>
 *
 * <h2>长度上限的依据</h2>
 * 企业微信文本消息的 {@code content} 上限是 <b>2048 字节</b>，不是 2048 个字符。
 * {@code MAX_TEXT_CHARS} 按最坏情况（全中文，UTF-8 每字 3 字节）反推并在
 * {@link #push} 里按字节复核：声明侧给模型的是一道字符闸门，门面侧再兜一次字节闸门 ——
 * 只按字符判会在「600 个 emoji」这类输入上悄悄超限，而超限是被上游拒、不是被我们拒，
 * 届时用户看到的是一句没法自己修的报错。
 */
@Service
@ConditionalOnWeComEnabled
public class WeComSelfPushService {

    /**
     * 单条推送的字符上限。
     *
     * <p>600 而非 682（=2048/3）是为了让「全中文 + 组合字符」也塞得进 2048 字节。
     * 长文本（会议全文、邮件正文）应当先压成摘要再推 —— 工具描述里明写了这一条。
     */
    public static final int MAX_TEXT_CHARS = 600;

    /** 上游 {@code content} 的字节上限，来自企业微信文本消息规格。 */
    static final int MAX_TEXT_BYTES = 2048;

    /**
     * 不可用的同一句话。
     *
     * <p>三种原因（模块没启用 / 服务未装配 / 没绑定企微）对用户是同一件事：都推不出去，
     * 且都要去别的地方先做一步。给三种措辞只会让模型复述一个它分辨不出的差别。
     *
     * <p>{@code public} 是给助手工具用的：工具层拿不到 {@code channel.wecom.WeComSendService}，
     * 所以「功能没装配」这一档它只能自己去问门面；门面缺席时工具要有一句话可说，
     * 而<b>那句话只能有一份</b>（抄第二份必然与门面里的版本分叉）。
     */
    public static final String UNAVAILABLE_MESSAGE = "企业微信推送当前不可用：可能是企业微信功能没启用，"
            + "或者你的账号还没绑定企业微信";

    /**
     * 发送器<b>可能根本不存在</b> —— 它是 {@code @ConditionalOnWeComEnabled} 且要求
     * {@code app.wecom-suite-id} 非空。所以注入 {@link ObjectProvider}，缺席在调用时翻译成
     * {@link IllegalStateException}，而不是让这个类装配失败。
     */
    private final ObjectProvider<WeComSendService> senders;

    public WeComSelfPushService(ObjectProvider<WeComSendService> senders) {
        this.senders = senders;
    }

    /**
     * 把 {@code text} 推给 {@code userId} 绑定的那个企业微信账号。
     *
     * <p><b>这里没有「推给谁」这个参数</b>，收件人完全由调用方身份解析
     * （{@code sendToBoundUser} 内部走 {@code WeComUserBindingService.requireByUserId}）。
     * 这是刻意的：一个不存在的参数不可能被填错，模型也就无从把消息推给同事或客户。
     *
     * @throws IllegalStateException 功能不可用 / 没有绑定 / 应用未安装完成
     * @throws PushRejected          上游明确拒绝，确定没有送达
     * @throws OutcomeUnknown        结果未确认，<b>可能已经送达</b>
     */
    public PushOutcome push(UUID userId, String text) {
        String content = text == null ? "" : text.strip();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("推送内容不能为空");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            // 在本地拦下来：让用户看到「太长了，压一压」，而不是上游那句
            // 「invalid text size」——后者既不可读，也没告诉他能做什么。
            throw new IllegalArgumentException(
                    "推送内容过长，超过企业微信单条消息的上限（" + MAX_TEXT_BYTES + " 字节，"
                            + "约 " + MAX_TEXT_CHARS + " 个汉字），请压缩后再推");
        }

        WeComSendService sender = senders.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException(UNAVAILABLE_MESSAGE);
        }

        try {
            WeComSendService.SendResult result = sender.sendToBoundUser(userId, content);
            return new PushOutcome(result == null ? "" : result.messageId());
        } catch (WeComException e) {
            throw translate(e);
        }
    }

    /**
     * {@code WeComException} → 三档之一。
     *
     * <p>判「结果未知」靠的是 {@code upstreamErrcode} 缺席：{@code WeComSendService.send}
     * 只有在<b>真的读到了企微的响应体</b>并看到非零 {@code errcode} 时才带上它；
     * 它那个把一切异常吞成「企业微信消息发送失败」的兜底分支不带 —— 也就是说，
     * 不带 {@code upstreamErrcode} 的失败恰恰是「请求发出去了但没读到回复」那一类，
     * 而那正是「可能已经送达」。这个推断是这一层唯一一处非平凡的逻辑，写在这里免得后来人
     * 以为 {@code null} 是随手判的。
     */
    private static RuntimeException translate(WeComException e) {
        String code = e.code();
        if ("WECOM_BINDING_UNAVAILABLE".equals(code) || "WECOM_INSTALLATION_UNAVAILABLE".equals(code)) {
            // 「你还没绑定」与「应用还没装完」对用户都是「去找管理员/去绑定」，
            // 与他填的内容无关，所以走不可用档而不是拒绝档。
            return new IllegalStateException(UNAVAILABLE_MESSAGE, e);
        }
        if (e.upstreamErrcode() == null) {
            return new OutcomeUnknown("推送可能已经发出去了，但系统没能确认结果。请不要重发，"
                    + "先到企业微信里看一眼是否已经收到", e);
        }
        return new PushRejected("企业微信拒绝了这次推送：" + safeMessage(e), e);
    }

    /** 上游的 errmsg 可以回给用户；底层异常消息不回（同 {@code ToolExecutionException.INTERNAL} 的口径）。 */
    private static String safeMessage(WeComException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "未知原因" : message;
    }

    /**
     * 推送成功。
     *
     * <p>{@code messageId} 是企微返回的 {@code msgid}，可能为空串（企微不保证每次都回）——
     * 不为了好看编一个。
     */
    public record PushOutcome(String messageId) {
    }

    /** 上游明确拒绝：确定没有送达。 */
    public static class PushRejected extends RuntimeException {
        public PushRejected(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 结果未确认：<b>可能已经送达</b>，重试会产生第二条消息。 */
    public static class OutcomeUnknown extends RuntimeException {
        public OutcomeUnknown(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
