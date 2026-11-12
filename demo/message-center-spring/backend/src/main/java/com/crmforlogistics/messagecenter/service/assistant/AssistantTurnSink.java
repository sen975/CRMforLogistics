package com.crmforlogistics.messagecenter.service.assistant;

/**
 * 一轮对话在<b>进行中</b>的旁路通知：让界面能边收边显示，而不改变这一轮的结果。
 *
 * <h2>它只上报「过程」，「结果」仍然只有一个来源</h2>
 * {@code respond} 的返回值依旧是权威的那一份（{@link AssistantTurnResult}）。
 * 这里推出去的片段是**尽力而为**的：它可能被 {@link #reset()} 作废，也可能因为模型没按信封
 * 输出而一个都没有。展示层必须做到两件事，否则这套旁路会变成第二个真相来源：
 * <ol>
 *   <li>拿到最终结果时**覆盖**占位内容，而不是把片段与结果拼在一起；</li>
 *   <li>{@link #reset()} 之后把已显示的片段清空。</li>
 * </ol>
 *
 * <h2>实现必须非阻塞、不抛异常</h2>
 * 这些方法在读模型响应的线程上被调用。抛出去会和「供应商故障」混成同一个 503 ——
 * 把「用户关了页面」说成「AI 服务不可用」。所以实现要自己吞掉写流的 IO 失败。
 */
public interface AssistantTurnSink {

    /** 什么都不做的实现：同步调用方（既有测试、内部调用）用它。 */
    AssistantTurnSink NONE = new AssistantTurnSink() {
    };

    /** 已经开始问模型了、还没有可展示的内容。一轮至多一次。 */
    default void thinking() {
    }

    /**
     * 这一轮决定先去查只读工具，{@code tool} 是工具名。
     *
     * <p>带上工具名不是为了给用户看（展示层可以只显示「正在查询」），而是为了让「卡住了」
     * 这类反馈能定位到具体是哪一步 —— 一次用户请求最多会走 3 轮只读。
     */
    default void reading(String tool) {
    }

    /**
     * 答案的一个<b>新</b>片段（不是累积文本），展示层直接追加。
     *
     * <p>拼起来必定是最终答案的前缀 —— 这条不变量由 {@link AssistantReplyDeltaExtractor} 保证。
     */
    default void answerDelta(String text) {
    }

    /**
     * 之前发出去的片段作废：信封没写对，这一轮要重问。
     *
     * <p>展示层应当清空已经显示的片段。没有这个信号，用户会看到答案「自己改写自己」——
     * 那不是错误，而是模型输出被截断后重试的正常路径。
     */
    default void reset() {
    }
}
