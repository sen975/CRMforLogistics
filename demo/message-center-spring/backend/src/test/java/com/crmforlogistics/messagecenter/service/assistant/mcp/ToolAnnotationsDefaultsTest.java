package com.crmforlogistics.messagecenter.service.assistant.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注解默认值：<b>「没写」必须被渲染成规范的保守值，而不是 Java 的 {@code false}</b>。
 *
 * <p>这是本设计里最容易悄悄出错、且出错后完全看不见的地方。MCP 规范的默认值刻意保守：
 * 不写 {@code destructiveHint} 按 {@code true} 解释、不写 {@code readOnlyHint} 按 {@code false} 解释。
 * 一旦实现里用原始 {@code boolean} 接住这些字段，未设置就会变成 {@code false}，
 * 于是「可能破坏数据」被渲染成「安全」—— 提示词会照此告诉模型「这个动作随便调」。
 */
class ToolAnnotationsDefaultsTest {

    @Test
    void absentAnnotationsFallBackToSpecificationDefaults() {
        Map<String, Object> rendered = ToolRegistry.normaliseAnnotations(null);

        assertThat(rendered)
                .as("规范默认：不声明就是「可能破坏性、非只读、非幂等、触外部世界」")
                .containsEntry("readOnlyHint", false)
                .containsEntry("destructiveHint", true)
                .containsEntry("idempotentHint", false)
                .containsEntry("openWorldHint", true);
    }

    @Test
    void partiallySetAnnotationsKeepTheirValuesAndTheRestTakeDefaults() {
        McpSchema.ToolAnnotations annotations = McpSchema.ToolAnnotations.builder()
                .destructiveHint(false)
                .build();

        Map<String, Object> rendered = ToolRegistry.normaliseAnnotations(annotations);

        assertThat(rendered).containsEntry("destructiveHint", false);
        assertThat(rendered)
                .as("显式说了 destructive 无害，不等于只读，也不等于不触外部世界")
                .containsEntry("readOnlyHint", false)
                .containsEntry("openWorldHint", true)
                .containsEntry("idempotentHint", false);
    }

    @Test
    void explicitlySetValuesArePreserved() {
        McpSchema.ToolAnnotations annotations = McpSchema.ToolAnnotations.builder()
                .readOnlyHint(true)
                .destructiveHint(false)
                .idempotentHint(true)
                .openWorldHint(false)
                .build();

        assertThat(ToolRegistry.normaliseAnnotations(annotations))
                .containsEntry("readOnlyHint", true)
                .containsEntry("destructiveHint", false)
                .containsEntry("idempotentHint", true)
                .containsEntry("openWorldHint", false);
    }

    /**
     * 记录我们依赖的 SDK 契约。若哪天 SDK 把未设置的 hint 从 {@code null} 压成 {@code false}，
     * 上面那条测试仍然会过（补默认值的逻辑是我们自己的），但 SDK 自己序列化到 wire 时会出错 ——
     * 所以这里把这个前提钉住，让变更在这里失败，而不是在提示词里静默走偏。
     */
    @Test
    void sdkKeepsUnsetHintsAsNullRatherThanFalse() {
        McpSchema.ToolAnnotations unset = McpSchema.ToolAnnotations.builder().build();

        assertThat(unset.readOnlyHint()).isNull();
        assertThat(unset.destructiveHint()).isNull();
        assertThat(unset.idempotentHint()).isNull();
        assertThat(unset.openWorldHint()).isNull();
    }

    @Test
    void renderedToolDeclarationsAreFullyExplicit() {
        // 每个工具的注解都显式声明了四个 hint，渲染结果里不允许出现 null。
        McpSchema.ToolAnnotations declared = McpSchema.ToolAnnotations.builder()
                .readOnlyHint(false)
                .destructiveHint(true)
                .idempotentHint(true)
                .openWorldHint(false)
                .build();

        assertThat(ToolRegistry.normaliseAnnotations(declared).values()).doesNotContainNull();
    }
}
