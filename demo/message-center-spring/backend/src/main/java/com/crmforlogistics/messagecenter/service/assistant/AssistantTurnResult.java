package com.crmforlogistics.messagecenter.service.assistant;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一轮对话的结果。同时也是 {@code POST /api/assistant/messages} 的响应体。
 *
 * <p>{@code @JsonInclude(NON_NULL)} 让「不适用」的字段整体缺席，而不是变成 {@code null}：
 * 前端按 {@code kind} 分支渲染时，"字段不存在" 比 "字段是 null" 更少歧义
 * （后者需要区分「后端说没有」与「后端忘了填」）。
 *
 * <p><b>{@code kind=EXECUTED} 必须只在动作真的成功之后出现。</b>
 * 把「已受理」「执行中」渲染成 {@code EXECUTED} 是这套交互里最贵的一个错误：
 * 用户看到「已删除」就不会再去核对，而待办还在那里。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantTurnResult(Kind kind, String message, List<String> missing, Proposal proposal,
                                  String errorCode, HistoryTrim historyTrim) {

    public enum Kind {
        /** 需要用户补充信息（缺参数，或指代有歧义）。 */
        QUESTION,
        /** 动作已落待确认，等用户在卡片上点确认。**此时还没有执行。** */
        CONFIRMATION_REQUIRED,
        /** 动作已完成。 */
        EXECUTED,
        /** 只是回话，没有动作。 */
        ANSWER,
        /** 这一轮失败了。{@code errorCode} 必填。 */
        ERROR
    }

    /**
     * 这一轮的语境里，**有多少更早的消息没有带上**。
     *
     * <p>存在的理由是一条容易被忽视的失败：历史超限时静默丢掉最旧的几轮，
     * 模型拿着一个断掉的开头照样自信作答，而用户不知道助手已经看不见前面了。
     * 那比报错更难发现 —— 报错至少会让人换一种问法。
     *
     * <p>报错本身不可接受（为了语境而拒绝一条指令是本末倒置，理由见
     * {@link AssistantRequestGuard} 的类注释），于是唯一的出路是让「丢了」这件事**被看见**。
     *
     * <p>为 {@code null}（字段整体缺席）表示**一条都没丢**。刻意不用 {@code 0}：
     * 那样前端每次都要判「等于 0 还是不存在」，而两者在语义上本就是一回事。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryTrim(int droppedMessages) {
        public HistoryTrim {
            // 只有在真的丢了东西时才允许出现。传 0 进来是调用方的错，不是「没裁剪」的表达方式。
            if (droppedMessages <= 0) {
                throw new IllegalArgumentException("historyTrim requires droppedMessages > 0");
            }
        }
    }

    /**
     * 附上「历史被裁剪」这一事实。
     *
     * <p>为什么是实例方法而不是构造参数：它的取值只有一处知道（{@link AssistantRequestGuard}
     * 算出的 {@code droppedHistoryMessages}），做成构造参数会让每一个构造点都要考虑它 ——
     * 而绝大多数构造点（{@code confirm} / {@code cancel} / 只读轮）根本没有「这一轮的语境」可言。
     * 由唯一握着答案的那一处附加到结果上，这条边界才清楚。
     *
     * <p><b>调用点自 2026-09-23 起是 {@link AssistantConversationService}</b>（此前是控制器）：
     * 只有编排层同时握着「选中的那份历史」与「裁剪结果」，也才说得清这个数字算的是哪一份。
     * 控制器不再触碰历史 —— 它只把线上的形状归一成领域对象。
     */
    public AssistantTurnResult withTrimmedHistory(int droppedMessages) {
        if (droppedMessages <= 0) {
            return this;
        }
        return new AssistantTurnResult(kind, message, missing, proposal, errorCode,
                new HistoryTrim(droppedMessages));
    }

    /**
     * 确认卡片的载荷。
     *
     * <p>{@code summary} 必须带**标题与日期** —— 这是歧义消解的唯一手段（设计文档 §3.2）：
     * 自然语言匹配必然有歧义，真正的安全网不是让匹配更聪明，而是让「执行前的人眼复核」
     * 足够便宜。只显示一句「确认要标记完成吗？」，用户无法判断模型认的是哪一条。
     *
     * <p>{@code arguments} 只是只读展示。确认接口只接受 {@code pendingActionId}，
     * 前端**无法**通过改这里的参数把确认落到别处 —— 那正是把授权落库而不是交给前端的理由。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Proposal(UUID pendingActionId, String tool, String summary,
                           List<Change> changes, Map<String, Object> arguments) {

        public Proposal {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }

        /**
         * 「改前 → 改后」的一条。
         *
         * <p><b>这里刻意不标 {@code @JsonInclude(NON_NULL)}</b>，与承载它的
         * {@link Proposal}（以及 {@link AssistantTurnResult}）口径相反。那两个类过滤 null，
         * 是为了区分「不适用」与「后端忘了填」；而这里的 {@code before = null} **本身就是一个取值**：
         * 服务端拿不到「改前」的证据。让它显式为 {@code null}，前端才能把它渲染成
         * 「当前未知」而不是一片空白 —— 空白会被读成「没有变化」。
         *
         * <p>{@code before} 绝不能靠推断补齐。凭空写一个「改前」的值比留空更危险：
         * 用户会拿它当事实去核对，而它是编的。拿不到证据就留 null，这是这张卡片能不能被信任的分界线。
         */
        public record Change(String field, String label, String before, String after) {
        }
    }

    public static AssistantTurnResult question(String question, List<String> missing) {
        return new AssistantTurnResult(Kind.QUESTION, question, missing, null, null, null);
    }

    public static AssistantTurnResult confirmationRequired(Proposal proposal) {
        return new AssistantTurnResult(Kind.CONFIRMATION_REQUIRED,
                "请确认后我再执行：" + proposal.summary(), null, proposal, null, null);
    }

    public static AssistantTurnResult executed(String message) {
        return new AssistantTurnResult(Kind.EXECUTED, message, null, null, null, null);
    }

    public static AssistantTurnResult answer(String message) {
        return new AssistantTurnResult(Kind.ANSWER, message, null, null, null, null);
    }

    public static AssistantTurnResult error(String errorCode, String message) {
        return new AssistantTurnResult(Kind.ERROR, message, null, null, errorCode, null);
    }
}
