package com.crmforlogistics.messagecenter.service.assistant;

import java.util.List;

/**
 * 一组「有界候选」：本轮模型<b>只能引用这里出现过的 id</b>。
 *
 * <h2>为什么候选集是这套助手的中心概念，而不是一个数据容器</h2>
 * 这个助手是单轮 JSON 决策（{@code AssistantModelClient} 只是 chat completions 包装，没有
 * {@code tool_calls}，也不发 {@code response_format}）。也就是说<b>模型不会「先搜一下再决定」</b>：
 * 它能引用的对象，必然是服务端在提问之前就喂给它的那些。于是「待办能被匹配」不是因为模型聪明，
 * 而是因为 {@code listOpenForAssistant} 天然有界（≤70）。
 *
 * <p>第二个域一进来（联系人几千条、还会重名；会话上百条），压力全部落在这个接缝上：
 * <b>候选从哪来、怎么有界、怎么渲染、怎么判定归属</b>。所以这里把它抽成一个显式的声明，
 * 让「加一个域」的成本变成「加一组候选 + 一个工具声明」，而不是改编排层。
 *
 * <h2>它同时是三道闸门的落点</h2>
 * <ol>
 *   <li><b>有界</b>：{@link #limit()} 是硬上限。渲染进提示词的条数、只读工具返回的条数，
 *       都不得越过它 —— 这既是成本控制，也是数据外泄面控制。</li>
 *   <li><b>可比对</b>：{@link #contains(String)} 是「模型编造 id」的第一道拦截
 *       （第二道是 SQL 里的 {@code where user_id}）。工具通过入参 schema 里的
 *       {@code x-candidateSet} 声明它引用哪一组，解析器据此判定。</li>
 *   <li><b>不可信</b>：{@link #items()} 里的文本（待办标题、联系人备注）是<b>用户可控的自由文本</b>，
 *       提示词里用分隔符包裹并声明为不可信数据。候选集只负责搬运，<b>不做任何转义或改写</b> ——
 *       改写会掩盖注入而不会消除它。</li>
 * </ol>
 *
 * <h2>刻意不提供 {@code resolve(userId, text)}</h2>
 * 「按本轮文本检索候选」看起来该放在这里，但它需要域自己的仓储（待办、会话、联系人各不相同），
 * 而候选集是个纯数据对象、要能被无依赖地构造与断言。因此检索留在 provider（
 * {@code AssistantContextBuilder} / 只读工具），候选集只声明「我是谁、我最多几条、我包含什么」。
 * 这样单元测试可以手写一组候选，不必拉起任何仓储。
 */
public interface CandidateSet {

    /** 集合名。工具用 {@code inputSchema} 里字段级的 {@code x-candidateSet} 引用它。 */
    String name();

    /** 提示词里这一节的标题。写在这里而不是编排层，理由同「加一个域只加一组声明」。 */
    String heading();

    /** 条数硬上限。 */
    int limit();

    /** 进提示词的可序列化形状，保序。 */
    List<?> items();

    /** 该 id 是否属于本集合。 */
    boolean contains(String id);
}
