package com.crmforlogistics.messagecenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 助手（AI 悬浮按钮）配置。前缀 {@code assistant}。
 *
 * <h2>与 {@code ai-topic} 的关系</h2>
 * 凭据沿用同一组环境变量（{@code AI_BASE_URL} / {@code AI_API_KEY} / {@code AI_MODEL}），
 * 但**配置项独立**：助手的默认模型与超时和话题聚类不是一回事，将来把助手切到别的模型时
 * 不应该连带影响话题聚类。这也是这里不直接复用 {@code AiTopicConfig} 的原因。
 *
 * <h2>越界在构造期就抛</h2>
 * 照 {@code AiTopicConfig} 的写法逐项校验。理由不是洁癖：这些值全都参与**提示词体积**与
 * **单轮成本**的计算（历史多少轮、候选多少条），配错一个量级不会报错，只会让每次调用的
 * token 数悄悄涨十倍。
 *
 * <h2>默认关闭</h2>
 * {@code enabled} 默认 {@code false}，且 {@code base-url} / {@code api-key} 为空时
 * 编排服务整体不装配（见 {@link ConditionalOnAssistantEnabled}）。三者是「与」关系：
 * 显式开启、且凭据齐备，才注册端点。
 */
@ConfigurationProperties(prefix = "assistant")
public record AssistantConfig(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("gpt-4o-mini") String model,
        @DefaultValue("30") int timeoutSeconds,
        @DefaultValue("2000") int maxMessageChars,
        @DefaultValue("8") int maxHistoryTurns,
        @DefaultValue("8000") int maxHistoryChars,
        @DefaultValue("70") int candidateTodoLimit,
        @DefaultValue("600") int pendingTtlSeconds,
        /**
         * 一次请求内允许执行的<b>只读工具次数</b>上限（默认 3；0 表示关掉只读轨，退化为单轮）。
         *
         * <p>放在记录末尾是刻意的：这个 record 的构造器是位置参数，插在中间会让既有调用
         * 按位置错配（比如把 {@code pendingTtlSeconds} 当成轮数），而那种错误不会有任何编译或运行时提示。
         */
        @DefaultValue("3") int maxReadTurns
) {

    /** 与 {@code TodoItemService.ASSISTANT_CANDIDATE_MAX_LIMIT} 同值：候选窗口硬上限。 */
    public static final int CANDIDATE_LIMIT_CEILING = 100;

    /**
     * 只读轮数的硬上限。
     *
     * <p>上限低不是因为成本，而是因为「多轮的收益递减、风险递增」：模型看过一轮结果之后
     * 再问一轮，能补的信息已经很少；而每一轮都是一次新的决策机会（也是新的越狱面）。
     * 5 轮已经足够覆盖「先查会话、再查细节、再动手」这类真实需求。
     */
    public static final int MAX_READ_TURNS = 5;

    @ConstructorBinding
    public AssistantConfig {
        if (timeoutSeconds <= 0 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("assistant timeoutSeconds must be 1..300");
        }
        if (maxMessageChars <= 0 || maxMessageChars > 20_000) {
            throw new IllegalArgumentException("assistant maxMessageChars must be 1..20000");
        }
        // 0 是允许的：不带历史的一次性提问是合法用法，不该被配置校验挡下。
        if (maxHistoryTurns < 0 || maxHistoryTurns > 50) {
            throw new IllegalArgumentException("assistant maxHistoryTurns must be 0..50");
        }
        if (maxHistoryChars < 0 || maxHistoryChars > 100_000) {
            throw new IllegalArgumentException("assistant maxHistoryChars must be 0..100000");
        }
        // 上限与服务层的候选窗口硬上限对齐：允许配得更大只会得到一个被静默压回去的假配置。
        if (candidateTodoLimit <= 0 || candidateTodoLimit > CANDIDATE_LIMIT_CEILING) {
            throw new IllegalArgumentException(
                    "assistant candidateTodoLimit must be 1.." + CANDIDATE_LIMIT_CEILING);
        }
        if (pendingTtlSeconds < 30 || pendingTtlSeconds > 86_400) {
            throw new IllegalArgumentException("assistant pendingTtlSeconds must be 30..86400");
        }
        // 0 是允许的，而且是回滚开关：只读轨关掉即退化为单轮决策（L0）。
        // 负数则会让「还能不能再查一轮」这个问题失去意义，所以不接受。
        if (maxReadTurns < 0 || maxReadTurns > MAX_READ_TURNS) {
            throw new IllegalArgumentException("assistant maxReadTurns must be 0.." + MAX_READ_TURNS);
        }
    }
}
