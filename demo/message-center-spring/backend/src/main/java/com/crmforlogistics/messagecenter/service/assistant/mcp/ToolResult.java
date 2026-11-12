package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.service.assistant.CandidateSet;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次工具调用的结果。
 *
 * <p>与传输无关：编排层拿它决定怎么回话，MCP 适配层拿它构造 {@code CallToolResult}。
 * {@code data} 放结构化结果（如新建待办的 id 与日期），让上游既能生成自然语言回复，
 * 也能在前端卡片上显示确定的内容 —— 不要求上游去解析 {@code message}。
 *
 * @param isError    是否失败。对应 MCP 的 {@code CallToolResult.isError}。
 * @param code       失败时的错误码，见 {@link ToolExecutionException}；成功时为 {@code null}。
 * @param message    给人看的一句话。成功时也可以有（如「已创建：9月22日 15:00 和张总确认报价」）。
 * @param data       结构化结果，成功时通常非空；失败时一般为空 map。
 * @param candidates 这次调用<b>发现</b>的候选集，用于下一轮引用；没有发现时是 {@code null}。
 */
public record ToolResult(boolean isError, String code, String message, Map<String, Object> data,
                         CandidateSet candidates) {

    public ToolResult {
        // 刻意不用 Map.copyOf：它拒绝 null 值，而待办的 time / note 本来就可以为空，
        // 上层要能区分"这条待办没有时间"与"这次结果里不含 time 字段"。
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /**
     * 只读工具专用的构造：把「这次检索发现了哪些对象」交回编排层。
     *
     * <h2>为什么候选必须由工具交回，而不是编排层去解析 {@code data}</h2>
     * 候选的形状是<b>每个域自己的</b>（待办要 id/日期/标题，会话要 type/id/名称/渠道）。
     * 让编排层从 {@code data} 这个自由形状的 map 里反推候选，等于把域知识塞回编排层 ——
     * 那正是「加一个域要改编排层」的老问题。工具自己构造候选集，编排层只按名字替换
     * （{@link com.crmforlogistics.messagecenter.service.assistant.AssistantContext#withCandidateSet}）。
     *
     * <p>{@code data} 仍然会被完整地渲染进 observation：模型需要看到检索内容才能回话，
     * 候选只是「它下一轮能不能引用这些 id」的机制，两者不重复也不互相替代。
     */
    public static ToolResult discovered(String message, Map<String, Object> data, CandidateSet candidates) {
        return new ToolResult(false, null, message, data, candidates);
    }

    public static ToolResult ok(String message, Map<String, Object> data) {
        return new ToolResult(false, null, message, data, null);
    }

    public static ToolResult failure(String code, String message) {
        return new ToolResult(true, code, message, Map.of(), null);
    }
}
