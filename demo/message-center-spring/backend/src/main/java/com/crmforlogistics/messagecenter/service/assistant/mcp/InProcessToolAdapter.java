package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 进程内的 MCP 适配层：把 {@link ToolRegistry} 以 {@code tools/list} / {@code tools/call} 的语义暴露。
 *
 * <h2>为什么是进程内，而不是起一个 HTTP 自连</h2>
 * 阶段 0.1 实测确认：{@code mcp-core} 自带 Servlet 传输，走 HTTP 也能安全传身份
 * （{@code contextExtractor} → {@code McpSyncServerExchange.transportContext()}），
 * 并且模型偷塞 {@code userId} 会被 schema 的 {@code additionalProperties: false} 拒掉。
 * 也就是说 HTTP 是<b>可行</b>的，只是本期不需要：
 *
 * <ul>
 *   <li>SDK <b>没有</b>进程内传输，走 HTTP 就得真起一个 Servlet、多一跳网络、多一份 session 生命周期，
 *       而调用方（编排层）与工具本来就在同一个请求线程里；</li>
 *   <li>当前唯一的消费者是本进程的助手，没有外部 host 要用这个端点；</li>
 *   <li>{@code /mcp} 一旦暴露还必须单独加认证规则（既有 {@code SecurityConfig} 的
 *       {@code anyRequest().permitAll()} 会让它变成公开端点），那是阶段 5 的活。</li>
 * </ul>
 *
 * <h2>这不是"没做 MCP"</h2>
 * 值钱的是声明模型，不是字节。{@link McpSchema.Tool} 用的就是官方类型，
 * 将来补一个 HTTP 适配层时，本类<b>一行都不用改</b>，工具与策略代码也不用改 ——
 * 这正是把注册表与传输分开的收益。
 *
 * <h2>身份只从认证上下文来</h2>
 * {@code userId} 取自 {@link SecurityUtil}，<b>绝不取自 {@code arguments}</b>。
 * schema 里也没有任何身份字段，模型无从指定身份。取不到就让它抛（上层变 401），不做降级。
 */
@Component
public class InProcessToolAdapter {

    private final ToolRegistry registry;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public InProcessToolAdapter(ToolRegistry registry, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /** 等价于 MCP 的 {@code tools/list}：把注册表里的声明原样吐出。 */
    public McpSchema.ListToolsResult listTools() {
        return new McpSchema.ListToolsResult(
                registry.list().stream().map(ToolDefinition::tool).toList(), null);
    }

    /**
     * 等价于 MCP 的 {@code tools/call}，身份由当前认证上下文提供。
     *
     * <p>{@link SecurityUtil#currentUserId()} 在未认证时抛 {@code SecurityException}，
     * 刻意不在这里 catch 成"操作失败" —— 那会把"这个请求没有身份"伪装成"动作执行不了"。
     */
    public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
        return callTool(name, SecurityUtil.currentUserId(), arguments);
    }

    /** 显式指定身份的重载：供测试与将来的非 HTTP 调用方（如定时任务）使用。 */
    public McpSchema.CallToolResult callTool(String name, UUID userId, Map<String, Object> arguments) {
        return toCallToolResult(registry.invoke(name, userId, arguments));
    }

    /**
     * 结果映射：结构化结果以 JSON 文本承载。
     *
     * <p>没有用 {@code structuredContent}：那要求同时声明 {@code outputSchema}，
     * 而输出结构会随工具演进，现在把 schema 钉死只会变成负担。
     * 文本里给的是确定形状的 JSON（{@code ok/code/message/data}），上游要解析也解析得动。
     */
    private McpSchema.CallToolResult toCallToolResult(ToolResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", !result.isError());
        if (result.code() != null) {
            payload.put("code", result.code());
        }
        payload.put("message", result.message());
        payload.put("data", result.data());
        String text;
        try {
            text = objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 序列化失败不该让整轮崩掉，但也不能装作成功：保留错误语义，降级成一句纯文本。
            text = "{\"ok\":false,\"code\":\"" + ToolExecutionException.INTERNAL + "\",\"message\":\"结果无法序列化\"}";
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .isError(result.isError())
                .build();
    }
}
