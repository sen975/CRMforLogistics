package com.crmforlogistics.messagecenter.service.todo;

/**
 * 目标待办不存在，或者存在但不属于当前用户。
 *
 * <p><b>这两种情况刻意共用同一个类型与文案</b>：区分开就等于提供一个"这个 id 是存在的，
 * 只是不是你的"的探测信道，调用方只需枚举 id 就能探出别人的待办是否存在。
 * 对使用者来说两种情况的处置也完全一样 —— 重新匹配或放弃，没有可做的区别。
 *
 * <p>继承 {@link IllegalArgumentException} 而不是裸 {@link RuntimeException}，
 * 是为了不改变既有调用方的异常契约：{@code TodoItemService.setCompleted} /
 * {@code delete} 历史上就以 {@code IllegalArgumentException} 表示"待办不存在"，
 * 任何 catch 住它的代码在换成这个子类后行为不变。工具层则按更具体的类型区分错误码。
 */
public class TodoItemNotFoundException extends IllegalArgumentException {
    public TodoItemNotFoundException() {
        super("待办不存在");
    }
}
