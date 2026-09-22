package com.crmforlogistics.messagecenter.service.assistant.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Objects;

/**
 * 一条工具声明：<b>怎么描述</b> + <b>怎么做</b>。
 *
 * <p>声明部分直接复用官方 SDK 的 {@link McpSchema.Tool}，不做自有模型再转换：
 *
 * <ul>
 *   <li>SDK 已经用<b>可空 {@code Boolean}</b> 正确建模了三个 hint —— 未显式设置就是 {@code null}，
 *       序列化时整个 {@code annotations} 字段缺席，不会被压成 {@code false}。
 *       自造一个 record + 原始 {@code boolean} 恰好会踩反默认值（规范里
 *       {@code destructiveHint} 不写按 {@code true} 算），等于把「未知」报成「安全」。</li>
 *   <li>将来要暴露 {@code /mcp} 端点时，这份声明可以原样进 {@code tools/list}，零映射。</li>
 * </ul>
 *
 * <p>注意这是一份<b>声明</b>而不是策略：{@code annotations} 只用于提示模型，能否直接执行由
 * 编排层的白名单判定（MCP 规范明确要求客户端把注解当不可信输入）。
 */
public record ToolDefinition(McpSchema.Tool tool, ToolHandler handler) {

    public ToolDefinition {
        Objects.requireNonNull(tool, "tool 不能为空");
        Objects.requireNonNull(handler, "handler 不能为空");
        Objects.requireNonNull(tool.name(), "工具名不能为空");
    }

    public String name() {
        return tool.name();
    }

    /** 该工具入参 schema 里声明为必填的字段名。 */
    public java.util.List<String> requiredArguments() {
        Object required = tool.inputSchema() == null ? null : tool.inputSchema().get("required");
        if (required instanceof java.util.List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return java.util.List.of();
    }

    /**
     * 「参数名 → 候选集名」的绑定，读自字段级的 {@code x-candidateSet}。
     *
     * <p>解析器用它做两件事：把参数值与该组候选比对（拦「模型编造 id」），
     * 以及在解析失败时给出说得清原因的拒绝理由。找不到绑定的字段就是普通参数，
     * 不做任何比对 —— 这一点由 {@link ToolRegistry#selfCheck} 的 {@code *Id} 规则兜住：
     * 忘记绑定会起不来，而不是静默地放过。
     *
     * <p>保序（{@code LinkedHashMap}），让拒绝理由的措辞稳定可断言。
     */
    public java.util.Map<String, String> referenceBindings() {
        Object properties = tool.inputSchema() == null ? null : tool.inputSchema().get("properties");
        if (!(properties instanceof java.util.Map<?, ?> declared)) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> bindings = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<?, ?> entry : declared.entrySet()) {
            if (entry.getValue() instanceof java.util.Map<?, ?> field
                    && field.get(ToolInputValidator.CANDIDATE_SET) instanceof String setName
                    && !setName.isBlank()) {
                bindings.put(String.valueOf(entry.getKey()), setName.strip());
            }
        }
        return java.util.Map.copyOf(bindings);
    }

    /** {@code x-unboundIds} 的取值：显式声明「这些 id 不来自候选」，用于豁免 {@code *Id} 检查。 */
    public java.util.List<String> unboundIdExemptions() {
        Object declared = tool.inputSchema() == null ? null : tool.inputSchema().get(ToolInputValidator.UNBOUND_IDS);
        if (declared instanceof java.util.List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return java.util.List.of();
    }
}
