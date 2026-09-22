package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 入参整理：本轮原话超长即报错，历史超限则按「保留最近」裁剪。 */
class AssistantRequestGuardTest {

    private final AssistantRequestGuard guard = new AssistantRequestGuard(
            new AssistantConfig(false, "", "", "gpt-4o-mini", 30, 100, 3, 30, 70, 600, 3));

    @Test
    void blankTextIsRejected() {
        assertThatThrownBy(() -> guard.normalise(List.of(), "   "))
                .isInstanceOf(AssistantException.class)
                .hasMessageContaining("请先输入");
    }

    @Test
    void oversizeTextIsRejectedRatherThanTruncated() {
        // 静默截断一份指令会让模型按半个诉求执行，比拒绝危险得多。
        assertThatThrownBy(() -> guard.normalise(List.of(), "x".repeat(101)))
                .isInstanceOf(AssistantException.class)
                .hasMessageContaining("太长");
    }

    @Test
    void textIsTrimmed() {
        assertThat(guard.normalise(List.of(), "  帮我建个待办  ").text()).isEqualTo("帮我建个待办");
    }

    @Test
    void historyKeepsTheMostRecentTurnsFirst() {
        List<AssistantMessage> history = List.of(
                AssistantMessage.user("第一轮"),
                AssistantMessage.assistant("第二轮"),
                AssistantMessage.user("第三轮"),
                AssistantMessage.assistant("第四轮"));

        List<AssistantMessage> kept = guard.normalise(history, "现在这句").history();

        assertThat(kept).hasSize(3);
        assertThat(kept).extracting(AssistantMessage::text)
                .containsExactly("第二轮", "第三轮", "第四轮");
    }

    @Test
    void historyIsAlsoBoundedByCharacters() {
        AssistantRequestGuard narrow = new AssistantRequestGuard(
                new AssistantConfig(false, "", "", "gpt-4o-mini", 30, 100, 8, 10, 70, 600, 3));

        List<AssistantMessage> kept = narrow.normalise(List.of(
                AssistantMessage.user("12345678"),
                AssistantMessage.assistant("abcdefgh")), "现在这句").history();

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).text()).isEqualTo("abcdefgh");
    }

    @Test
    void blankHistoryEntriesDoNotConsumeTheBudget() {
        List<AssistantMessage> history = new ArrayList<>();
        history.add(AssistantMessage.user("   "));
        history.add(null);
        history.add(AssistantMessage.user("有效的一轮"));

        List<AssistantMessage> kept = guard.normalise(history, "现在这句").history();

        assertThat(kept).extracting(AssistantMessage::text).containsExactly("有效的一轮");
    }

    @Test
    void systemRoleFromTheWireCannotImpersonateTheSystemPrompt() {
        // 角色映射只认 user / assistant；system 落到 user，因此前端无法覆写硬规则。
        assertThat(AssistantMessage.Role.fromWire("system")).isEqualTo(AssistantMessage.Role.USER);
        assertThat(AssistantMessage.Role.fromWire("SYSTEM")).isEqualTo(AssistantMessage.Role.USER);
        assertThat(AssistantMessage.Role.fromWire(null)).isEqualTo(AssistantMessage.Role.USER);
        assertThat(AssistantMessage.Role.fromWire("")).isEqualTo(AssistantMessage.Role.USER);
        assertThat(AssistantMessage.Role.fromWire("assistant")).isEqualTo(AssistantMessage.Role.ASSISTANT);
        assertThat(AssistantMessage.Role.fromWire(" Assistant ")).isEqualTo(AssistantMessage.Role.ASSISTANT);
    }
}
