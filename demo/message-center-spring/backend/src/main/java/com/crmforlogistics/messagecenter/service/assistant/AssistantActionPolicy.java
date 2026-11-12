package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.service.assistant.mcp.AiTopicAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ChatAppTemplateAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ContactAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ContactTimelineAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ConversationAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.MessageAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.TodoAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolDefinition;
import com.crmforlogistics.messagecenter.service.assistant.mcp.WeComAssistantTools;
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
     * <p>当前两条：
     *
     * <ul>
     *   <li>{@code todo.create} —— 只增不改，最坏情况是多出一条用户能删掉的记录，
     *       不影响任何既有数据。{@code todo.complete} / {@code todo.delete} / {@code todo.update}
     *       都会改掉或抹掉已存在的数据，一律需要确认 —— 其中删除不可逆、改期会覆盖既有日期，
     *       风险都不低于标记完成。</li>
     *
     *   <li>{@code wecom.push_self}（2026-09-28 加）—— 把一段文本推送到<b>调用方自己</b>绑定的
     *       企业微信。它破了「对外动作一律过确认」那条惯例，所以理由必须写在这里：
     *       <b>收件人不是参数</b>（由 userId 解析，模型编不出别人），
     *       最坏后果是「自己多收一条自己让发的消息」，且用户说「推给我」之后本来就期望它立刻到达 ——
     *       再点一次确认没有新增任何判断依据。完整的取舍见 {@code WeComAssistantTools} 的类注释。
     *       <p><b>它与 {@code message.send_email} 的差别不在「发没发出去」，而在「发给谁」</b>：
     *       后者能给任意第三方，前者不能。所以这两条判据不是矛盾的，
     *       而是同一条判据（收件人由谁决定）在两个工具上得出的不同结论。</li>
     * </ul>
     *
     * <p>新增条目必须同时回答：「最坏情况用户会失去什么？」答不上来就不该进这个集合。
     * 对 {@code wecom.push_self} 的回答是「一条自己收到的消息」—— 注意这条答案之所以成立，
     * 靠的是它的收件人解析方式；把收件人改成参数的那一刻，它就该被移出这个集合。
     */
    public static final Set<String> AUTO_EXECUTE_ALLOWLIST = Set.of(
            TodoAssistantTools.TOOL_CREATE,
            WeComAssistantTools.TOOL_PUSH_SELF);

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
     *   <li><b>返回值不得越出当前口径</b>。这条无法由代码判定，写在注释里当 checklist ——
     *       它是四个条件里唯一需要人负责的。
     *
     *       <p><b>口径已在 2026-09-23 变更，写清楚免得后来人照旧注释去拦合规的事</b>：
     *       原口径（2026-09-22 选项 b）是「只允许结构化事实与摘要进模型，<b>原文与转写一律不出边界</b>」。
     *       现口径是<b>「允许原文进上下文」</b> —— 由用户在 {@code message.read}（C4）就绪前拍板。
     *       于是判据从「有没有原文」变成<b>「这份原文是不是用户点名要的那一份」</b>：
     *       一次回 20 条的时间线仍然要剥掉正文（那段正文不是用户要看的，还会挤掉真正的内容），
     *       而 {@code message.read} 取的是用户指名的那几条（2026-09-28 起一次可传一队引用，
     *       但仍是用户点名要的对象，不是「顺手捞一批」），所以它带原文是对的。
     *       「只读 = 不含原文」这条旧推论<b>不再成立</b>，别再用它否决新工具。</li>
     * </ol>
     *
     * <p>「可以循环」为什么是安全的关键：只读动作没有副作用，多轮也只会让回答更准；
     * 而写动作在一次请求里仍然至多一个，且必须过确认（约束 4）。这条分界让
     * 「先查再改」成为可能，同时不给「多写」开任何口子。
     *
     * <p>当前九条：会话检索、联系人检索、联系人简报、联系人往来时间线、联系人 AI 话题、
     * 单条消息原文、企微群聊天摘要、chatapp 模板清单、chatapp 素材清单。前五条只读库里的
     * 结构化字段与摘要；后四条的区别值得单独说明 ——
     *
     * <ul>
     *   <li>{@code message.read} 是<b>第一个故意回原文</b>的（见上面口径变更那一段）；</li>
     *   <li>{@code wecom.summary_read} 回的是<b>摘要</b>（企微侧已经算好的），
     *       所以它本身不越界；它进这个清单的真正原因是它底下补了一条按 owner 过滤的读路径
     *       （原先那条全链路没有 {@code where user_id}），可见性由此与「只看自己的」对齐。</li>
     *   <li>{@code chatapp.template_list}（2026-09-28 加）回的是模板的<b>元数据</b>
     *       （名称、语言、类别、审核状态、被拒原因），不是模板内容本身；
     *       而它底下的账号清单带着 {@code where owner_user_id}。
     *       它进这个清单还有一个附带作用：<b>它是 {@code chatapp.template_apply} 的候选来源</b>，
     *       而只读轨才允许「先查再写」，所以申请链要走通，这一条必须可循环。</li>
     *
     *   <li>{@code chatapp.template_media_list}（2026-09-29 加）回的也是<b>元数据</b>
     *       （类型、大小、上传时间），不是图片本身；它底下的账号清单带着
     *       {@code where owner_user_id}，素材那条查询按账号过滤、归属判在 SQL 里。
     *       它进这个清单的另一个前提是它<b>不返回图片地址</b>：素材在服务商侧那个公网 URL
     *       只留在服务层内部（见 {@code WhatsAppTemplateMediaCatalogService.MediaAsset}），
     *       否则一次只读调用就能拿到一批可分享的外链，「这份内容是不是用户点名要的那一份」
     *       这条口径就守不住了。</li>
     * </ul>
     *
     * <p><b>为什么"只读"这一档反而要逐个交代</b>：进这个集合 = 免确认<b>且可以在一次请求内循环多轮</b>。
     * 只读动作多轮只让回答更准，所以门槛不是"有没有副作用"（都没有），
     * 而是第 3 条 —— 返回内容有没有越出口径。{@code contact.timeline} 就是靠这条才被拦下来改造的：
     * 它底下的 {@code ContactTimelineService} 会返回消息正文，而一次 20 条不是"用户要的那份"。
     */
    public static final Set<String> READ_ONLY_ALLOWLIST = Set.of(
            ConversationAssistantTools.TOOL_SEARCH,
            ContactAssistantTools.TOOL_SEARCH,
            ContactAssistantTools.TOOL_BRIEF,
            ContactTimelineAssistantTools.TOOL_TIMELINE,
            AiTopicAssistantTools.TOOL_TOPICS_READ,
            MessageAssistantTools.TOOL_READ,
            WeComAssistantTools.TOOL_SUMMARY_READ,
            ChatAppTemplateAssistantTools.TOOL_TEMPLATE_LIST,
            ChatAppTemplateAssistantTools.TOOL_TEMPLATE_MEDIA_LIST);

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
