package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolDefinition;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 策略清单的启动自检：清单与工具声明对不上，就让应用起不来。
 *
 * <h2>为什么这值得一个类</h2>
 * {@link AssistantActionPolicy} 的两份清单（只读 / 免确认）是<b>代码里的权威</b>，
 * 而工具声明是另一处代码。两处不一致时没有任何运行时症状：
 *
 * <ul>
 *   <li>清单写错一个工具名（拼错、被重命名）→ 那个工具静默退化成「仍需确认」，
 *       或者更糟：本该确认的动作因为名字正好命中而免确认。</li>
 *   <li>只读清单里的工具没声明 {@code readOnlyHint=true} → 运行时 {@code decide} 会抛异常，
 *       但那时用户已经发了消息，看到的是一次 500；而在启动时发现，代价是零。</li>
 * </ul>
 *
 * <p>与 {@link ToolRegistry#selfCheck} 同一哲学：这些错误全都落在「运行时才暴露」与
 * 「永远不暴露」之间，宁可起不来。这里是同一模式的第二处实例，因此<b>刻意无条件装配</b>
 * —— 它是纯静态声明的校验，与功能开关无关，关掉助手也不该让一个错误的清单蒙混过关。
 */
@Component
public class AssistantPolicySelfCheck {

    private static final Logger log = LoggerFactory.getLogger(AssistantPolicySelfCheck.class);

    public AssistantPolicySelfCheck(ToolRegistry registry) {
        for (String name : AssistantActionPolicy.READ_ONLY_ALLOWLIST) {
            ToolDefinition definition = registry.find(name).orElseThrow(() -> new IllegalStateException(
                    "只读清单里的工具 " + name + " 没有注册 —— 清单写了一个不存在的名字，"
                            + "它既不会免确认也不会循环，只会静默地什么都不做"));

            McpSchema.ToolAnnotations annotations = definition.tool().annotations();
            if (!AssistantActionPolicy.declaredReadOnly(definition)) {
                throw new IllegalStateException("只读清单里的工具 " + name + " 没有声明 readOnlyHint=true。"
                        + "只读清单决定的是「免确认且可循环」，一个写动作混进来就能在无人复核的情况下连续执行，"
                        + "所以这个方向的不一致不允许退化成保守处理");
            }
            if (Boolean.TRUE.equals(annotations.destructiveHint())) {
                throw new IllegalStateException("只读清单里的工具 " + name + " 同时声明为破坏性（destructiveHint=true）——"
                        + "两个声明直接矛盾，必须改掉其中一个");
            }
            if (AssistantActionPolicy.AUTO_EXECUTE_ALLOWLIST.contains(name)) {
                throw new IllegalStateException("工具 " + name + " 同时在只读清单与免确认清单里。"
                        + "两个集合的语义不同（一个免确认且继续循环，一个免确认但终止本轮），重叠一定是想错了");
            }
        }

        for (String name : AssistantActionPolicy.AUTO_EXECUTE_ALLOWLIST) {
            registry.find(name).orElseThrow(() -> new IllegalStateException(
                    "免确认清单里的工具 " + name + " 没有注册 —— 清单名字写错时它不会「退化成需要确认」，"
                            + "而是让本该直接可用的能力凭空消失"));
        }

        log.info("助手策略自检通过：只读 {} 个、免确认 {} 个",
                AssistantActionPolicy.READ_ONLY_ALLOWLIST.size(),
                AssistantActionPolicy.AUTO_EXECUTE_ALLOWLIST.size());
    }
}
