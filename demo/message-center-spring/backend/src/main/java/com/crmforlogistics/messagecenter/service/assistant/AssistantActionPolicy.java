package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.service.assistant.mcp.ContactAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ConversationAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.TodoAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolDefinition;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 动作策略：这次调用是只读检索、直接执行，还是先落待确认等用户点确认。
 *
 * <h2>三档判定，全部 fail-closed</h2>
 * <pre>
 *   tool ∈ READ_ONLY_ALLOWLIST   → READ   （只读，免确认，允许循环）
 *   tool ∈ AUTO_EXECUTE_ALLOWLIST → AUTO   （只增不改，免确认，执行后本轮结束）
 *   其余                          → CONFIRM（落待确认，等人复核）
 * </pre>
 *
 * <h2>为什么白名单写死、不从注解推导</h2>
 * MCP 规范对此有明确要求：<b>注解只是 hint，客户端必须按不可信输入处理，不得作为安全依据</b>。
 * 注解的价值是让模型知道「哪些动作要谨慎」，而「能不能不打招呼就执行」必须由客户端自己持有权威。
 * 把策略交给服务端声明，等于让被审计的一方自己写审计规则 —— 这里尤其危险，因为工具声明是
 * 由本系统提供的，一旦将来某个工具声明错一个 {@code destructiveHint: false}，
 * 删除动作就会静默地变成无需确认。
 *
 * <h2>READ 档：只读免确认，但「只读」不采信注解</h2>
 * 引入只读工具之后必须新增这一档 —— 只读动作还要用户点确认是荒谬的。但判据仍然是
 * <b>客户端持有的清单</b>，注解只作一致性校验：清单内的工具若没有声明
 * {@code readOnlyHint=true}，直接判为配置错误并抛出（而不是默默按 CONFIRM 处理）。
 * 这里与 AUTO 档的处置<b>刻意不同</b>：
 *
 * <ul>
 *   <li>AUTO 档的不一致 → 退回 CONFIRM + WARN。因为那只是「少点一次」，退保守即可。</li>
 *   <li>READ 档的不一致 → 抛。因为它意味着「有人把一个写动作放进了只读清单」，
 *       而只读动作是<b>免确认且允许循环</b>的 —— 一旦成立，写动作就能在无人复核的情况下
 *       连续执行。这个方向的错误不能靠「退保守」兜住，必须让它在启动时就暴露
 *       （{@link AssistantPolicySelfCheck}）。</li>
 * </ul>
 *
 * <h2>不一致时按更保守的一边</h2>
 * 两种 AUTO 方向的不一致各有处置：
 *
 * <ul>
 *   <li><b>在白名单里，却被声明为破坏性</b>（或干脆没写注解 —— 规范里未声明即按
 *       {@code destructiveHint=true} 解释）→ 视为配置错误：<b>仍然要确认</b>，并打 WARN。
 *       「差点让一个破坏性动作免确认」这件事必须留下痕迹，否则它只会以「用户怎么少点了一次」
 *       的形式在很久以后才被发现。</li>
 *   <li><b>不在白名单，却被声明为无害</b> → 不构成冲突，确认是默认行为。
 *       这是 {@code todo.complete} 这类动作的正常处境，打日志只会变成噪声。</li>
 * </ul>
 *
 * <p>注意注解的缺省值必须<b>按规范</b>而非按 Java 的 {@code boolean} 默认值解释：
 * 未声明 {@code destructiveHint} 等于 {@code true}。若这里用 {@code Boolean.TRUE.equals(...)}
 * 去判，未声明会被当成 {@code false}（安全），恰好把「未知」报成「安全」。
 */
@Component
public class AssistantActionPolicy {

    private static final Logger log = LoggerFactory.getLogger(AssistantActionPolicy.class);

    /**
     * 可以免确认直接执行的动作。<b>只放「不会改动已有数据」的动作。</b>
     *
     * <p>当前只有 {@code todo.create}：它只增不改，最坏情况是多出一条用户能删掉的记录，
     * 不影响任何既有数据。{@code todo.complete} / {@code todo.delete} / {@code todo.update}
     * 都会改掉或抹掉已存在的数据，一律需要确认 —— 其中删除不可逆、改期会覆盖既有日期，
     * 风险都不低于标记完成。
     *
     * <p>新增条目必须同时回答：「最坏情况用户会失去什么？」答不上来就不该进这个集合。
     */
    public static final Set<String> AUTO_EXECUTE_ALLOWLIST = Set.of(TodoAssistantTools.TOOL_CREATE);

    /**
     * 只读动作清单：免确认，且<b>可以在一次请求内循环多轮</b>。
     *
     * <p>放进来的门槛与 AUTO 档不同，要同时满足三条（缺一条就是配置错误，启动即失败）：
     *
     * <ol>
     *   <li><b>注解里 {@code readOnlyHint=true}</b>。规范要求客户端把注解当不可信输入，
     *       所以注解在这里是<b>一致性校验</b>而不是依据：清单说它只读，注解也必须说它只读，
     *       两边对不上就是有人搞错了，而错误的方向可能是「写动作被当成只读」。</li>
     *   <li><b>不得同时出现在 {@link #AUTO_EXECUTE_ALLOWLIST}</b>：两个集合的语义不同
     *       （一个免确认但终止本轮，一个免确认且继续循环），重叠一定是想错了。</li>
     *   <li><b>返回值不得越出合规口径</b>（2026-09-22 选项 b：只允许结构化事实与摘要）。
     *       这条无法由代码判定，写在注释里当 checklist —— 它是这四个条件里唯一需要人负责的。</li>
     * </ol>
     *
     * <p>「可以循环」为什么是安全的关键：只读动作没有副作用，多轮也只会让回答更准；
     * 而写动作在一次请求里仍然至多一个，且必须过确认（约束 4）。这条分界让
     * 「先查再改」成为可能，同时不给「多写」开任何口子。
     *
     * <p>当前四个：会话检索、联系人检索、联系人简报，以及它们的共同前提 ——
     * 只读检索必须能<b>突破候选窗口</b>（检索结果替换候选集），否则「先查再改」在窗口之外无路可走。
     * 这三条都只读库里的结构化字段与摘要，不含消息原文与通话转写（口径 b）。
     */
    public static final Set<String> READ_ONLY_ALLOWLIST = Set.of(
            ConversationAssistantTools.TOOL_SEARCH,
            ContactAssistantTools.TOOL_SEARCH,
            ContactAssistantTools.TOOL_BRIEF);

    public enum Decision {
        /** 只读动作：免确认执行，结果回灌后继续下一轮。 */
        READ,
        /** 白名单内，且声明与白名单一致：直接执行，执行完本轮结束。 */
        AUTO,
        /** 一律落待确认，等用户在卡片上复核。 */
        CONFIRM
    }

    public Decision decide(ToolDefinition definition) {
        String toolName = definition.name();
        if (READ_ONLY_ALLOWLIST.contains(toolName)) {
            if (!declaredReadOnly(definition)) {
                // 抛而不是退化成 CONFIRM：见类注释「READ 档：只读免确认，但只读不采信注解」。
                throw new IllegalStateException("助手工具 " + toolName + " 在只读清单里，"
                        + "但没有声明 readOnlyHint=true —— 只读清单决定的是「免确认且可循环」，"
                        + "不一致时不能按更保守的一边兜住，请修正工具注解或移出只读清单");
            }
            return Decision.READ;
        }
        boolean allowlisted = AUTO_EXECUTE_ALLOWLIST.contains(toolName);
        if (!allowlisted) {
            return Decision.CONFIRM;
        }
        if (declaredDestructive(definition)) {
            log.warn("助手工具 {} 在白名单内，但注解声明为破坏性（或未声明，按规范视为破坏性）——"
                    + "按更保守的一边处理：仍需确认。这属于配置错误，请修正工具注解或移出白名单。", toolName);
            return Decision.CONFIRM;
        }
        return Decision.AUTO;
    }

    /** 注解缺省按 MCP 规范的保守值解释：未声明 = 可能破坏。 */
    static boolean declaredDestructive(ToolDefinition definition) {
        McpSchema.ToolAnnotations annotations = definition.tool().annotations();
        Boolean hint = annotations == null ? null : annotations.destructiveHint();
        return hint == null || hint;
    }

    /**
     * 只读声明必须<b>显式</b>为 {@code true}。
     *
     * <p>与 {@link #declaredDestructive} 用相反的缺省值，不是笔误：规范里
     * {@code readOnlyHint} 未声明按 {@code false} 解释（即「有可能写」）。
     * 两个方向的保守值都是「更危险的那一个」，这才是一致的。
     */
    static boolean declaredReadOnly(ToolDefinition definition) {
        McpSchema.ToolAnnotations annotations = definition.tool().annotations();
        return annotations != null && Boolean.TRUE.equals(annotations.readOnlyHint());
    }
}
