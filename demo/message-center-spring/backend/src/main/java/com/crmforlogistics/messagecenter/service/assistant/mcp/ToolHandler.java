package com.crmforlogistics.messagecenter.service.assistant.mcp;

import java.util.Map;
import java.util.UUID;

/**
 * 工具的执行体。
 *
 * <p><b>{@code userId} 是参数而不是从线程上下文里自己去取</b>，这是刻意的：
 * 工具是纯逻辑，不该知道"当前用户"从哪来（HTTP 的 SecurityContext、定时任务、测试夹具都可能是来源）。
 * 由调用方（MCP 适配层 / 编排层）负责在进入工具前解析出身份，解析不到就直接拒绝，不做降级。
 * 平白给工具一个"自己想办法找身份"的机会，就一定会有人为了跑通而在那里写一个兜底默认值。
 *
 * <p>实现约定：业务上可预期的失败用 {@link ToolExecutionException} 抛（会带错误码给用户），
 * 未预期的异常由注册表兜成 {@code INTERNAL}。
 */
@FunctionalInterface
public interface ToolHandler {
    ToolResult execute(UUID userId, Map<String, Object> arguments);
}
