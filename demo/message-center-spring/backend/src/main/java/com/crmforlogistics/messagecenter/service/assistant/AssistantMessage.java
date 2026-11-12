package com.crmforlogistics.messagecenter.service.assistant;

/**
 * 一条会话消息。会话语境由前端持有，服务端无状态，因此这里只承载「渲染进提示词」所需的最小结构。
 *
 * <p>角色只有两种：用户与助手。刻意不支持 {@code system} ——
 * 系统提示词由服务端从注册表渲染，若能由请求体提供，前端（或被改过的前端）就可以直接
 * 覆写全部硬规则，而硬规则是这套系统的安全边界之一（阶段 0.3 的结论）。
 */
public record AssistantMessage(Role role, String text) {

    public enum Role {
        USER,
        ASSISTANT;

        /**
         * 把请求体里的角色字符串映射成枚举。<b>未知值一律落到 {@link #USER}</b>。
         *
         * <p>这条兜底是安全相关的：如果允许请求体声明 {@code system}，前端（或被改过的前端）就能
         * 直接覆写系统提示词里的硬规则，而硬规则是这套系统的安全边界之一（阶段 0.3 的结论）。
         * 落到 {@code USER} 而不是抛异常，是因为「角色写错」不该让整条指令失败 ——
         * 最坏的结果只是这段历史按用户内容参与渲染，而那正好是最保守的解释。
         */
        public static Role fromWire(String value) {
            return value != null && "assistant".equalsIgnoreCase(value.trim()) ? ASSISTANT : USER;
        }
    }

    public static AssistantMessage user(String text) {
        return new AssistantMessage(Role.USER, text);
    }

    public static AssistantMessage assistant(String text) {
        return new AssistantMessage(Role.ASSISTANT, text);
    }
}
