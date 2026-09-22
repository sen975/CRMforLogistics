package com.crmforlogistics.messagecenter.service.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个联系人的「简报」——{@code contact.brief} 的返回形状，<b>字段集合本身就是合规白名单</b>。
 *
 * <h2>为什么这里不是 {@code ContactMemoryContextService.load} 的结果</h2>
 * 计划 §6 B2 原本写的是「复用 {@code ContactMemoryContextService.load}，一次拿全画像/事实/标签/
 * 话题/通话转写」。落地时按 2026-09-22 定下的口径 b（<b>只允许结构化事实与摘要进模型，
 * 原文与通话转写一律不出边界</b>）改掉了，原因很具体：
 *
 * <ul>
 *   <li>{@code load} 的返回里带 <b>{@code inboundMessages}（客户消息原文）与
 *       {@code transcripts}（通话转写）</b>两节。它们是{@code load} 存在的理由 ——
 *       那个方法的用途是喂给<b>本系统自己的</b>记忆提炼流水线（模型在我们自己的部署里跑），
 *       而助手的返回会被渲染进提示词、发到<b>外部模型供应商</b>。同一个方法、
 *       两种去向，边界完全不同。</li>
 *   <li>{@code load} 还要求调用者就是联系人的归属人（{@code findCreatedBy} 对不上即
 *       {@code OWNER_MISMATCH}），而联系人列表页的授权口径更宽（管理员 / 团队分配 / 授权表）。
 *       用它会让「管理员看得到联系人、却问不了这个联系人的情况」变成一个无法解释的按钮。</li>
 * </ul>
 *
 * <p>因此简报走 {@code ContactMemoryMapper.listStableContext} —— 它返回的正好是
 * 画像 + 事实 + 标签 + 话题四节摘要，<b>结构上就没有</b>原文与转写可泄漏。这是个更强的保证：
 * 不是「我们记得别取它」，而是「这条路取不到它」。
 *
 * <h2>{@code memoryVisible=false} 是正常结果，不是错误</h2>
 * 画像/事实/标签都以联系人的 {@code created_by} 为归属人（见 {@code ContactMapper.findByIdAndOwner}
 * 与记忆表上的 {@code owner_user_id}）。所以一个由同事录入、你通过团队分配才看到的联系人，
 * 你能看到<b>他本人</b>（姓名、备注、角色）却看不到他的画像。此时返回一份只有基础信息的简报，
 * 并由 {@link #memoryVisible} 明说这件事 —— 比报错好，比假装「他没有画像」也好。
 */
public record ContactBrief(String contactRef, String name, String roleTitle, String remark,
                           boolean memoryVisible,
                           String profile,
                           List<Fact> facts,
                           List<String> aiLabels,
                           List<String> humanTags,
                           List<Topic> topics) {

    /**
     * 一条结构化事实。
     *
     * <p>{@code category} 与 {@code value} 都来自记忆流水线（{@code contact_memory_facts}），
     * 属于「结构化事实」而不是原文：它们是模型从消息里提炼出来的结论，已经过一次抽象。
     */
    public record Fact(String category, String value) {
    }

    /** 一条话题摘要（{@code ai_topics}）：标题 + 小结，同样是摘要而非原文。 */
    public record Topic(String title, String summary) {
    }

    public ContactBrief {
        facts = facts == null ? List.of() : List.copyOf(facts);
        aiLabels = aiLabels == null ? List.of() : List.copyOf(aiLabels);
        humanTags = humanTags == null ? List.of() : List.copyOf(humanTags);
        topics = topics == null ? List.of() : List.copyOf(topics);
    }

    /** 四节记忆是否有一条内容。基础信息（姓名/备注/角色）不计入。 */
    public boolean hasMemory() {
        return profile != null || !facts.isEmpty() || !aiLabels.isEmpty()
                || !humanTags.isEmpty() || !topics.isEmpty();
    }

    /**
     * 给模型的 JSON 形状。
     *
     * <p>放在这里而不是工具里：这个 record 的字段就是白名单，多一层手写的 map 映射就等于
     * 白名单有了第二份，早晚会出现「record 里加了字段、data 里忘了加」或反过来的分叉。
     * 用 {@link LinkedHashMap} 保序，让 observation 的字节内容稳定（提示词与快照测试依赖它）。
     */
    public Map<String, Object> toData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contactRef", contactRef);
        data.put("name", name);
        data.put("roleTitle", roleTitle);
        data.put("remark", remark);
        // 明说画像可见性，而不是让模型从「四节都空」去猜 ——
        // 「这位联系人没有画像」与「画像对你看不见」对用户是两件完全不同的事。
        data.put("memoryVisible", memoryVisible);
        data.put("profile", profile);
        data.put("facts", facts.stream()
                .map(fact -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("category", fact.category());
                    item.put("value", fact.value());
                    return item;
                })
                .toList());
        data.put("aiLabels", aiLabels);
        data.put("humanTags", humanTags);
        data.put("topics", topics.stream()
                .map(topic -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("title", topic.title());
                    item.put("summary", topic.summary());
                    return item;
                })
                .toList());
        return data;
    }
}
