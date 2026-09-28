package com.crmforlogistics.messagecenter.service.assistant;

import java.util.List;

/**
 * 待办候选集：{@code CandidateSet} 的第一个实例，也是既有行为的保持者。
 *
 * <p>抽出来的唯一目的是让「第二组候选」不需要改编排层。它自己的语义一个字都没变：
 * 上限由 {@code todo.search} 的有界 SQL 查询保证，条目形状是 {@link AssistantContext.CandidateTodo}。
 *
 * <p>条数上限刻意<b>只声明不截断</b>：限制发生在 {@code todo.search} 使用的 SQL limit。
 */
public record TodoCandidates(int limit, List<AssistantContext.CandidateTodo> items) implements CandidateSet {

    public static final String NAME = "todo";

    public TodoCandidates {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String heading() {
        return "当前用户的未完成待办（候选清单）";
    }

    @Override
    public boolean contains(String id) {
        return id != null && items.stream().anyMatch(candidate -> candidate.id().equals(id));
    }

    /** 按 id 取一条。取不到返回 {@code null}（确认卡片的摘要用它渲染标题与日期）。 */
    public AssistantContext.CandidateTodo find(String id) {
        if (id == null) return null;
        return items.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
    }
}
