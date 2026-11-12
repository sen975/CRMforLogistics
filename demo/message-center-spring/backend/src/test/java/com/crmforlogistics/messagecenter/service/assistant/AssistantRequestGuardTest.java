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
            new AssistantConfig(false, "", "", "gpt-4o-mini", 30, 100, 3, 30, 600, 3));

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
    void historyKeepsAllMessagesThatFitTheTokenBudget() {
        List<AssistantMessage> history = List.of(
                AssistantMessage.user("第一轮"),
                AssistantMessage.assistant("第二轮"),
                AssistantMessage.user("第三轮"),
                AssistantMessage.assistant("第四轮"));

        List<AssistantMessage> kept = guard.normalise(history, "现在这句").history();

        assertThat(kept).hasSize(4);
        assertThat(kept).extracting(AssistantMessage::text)
                .containsExactly("第一轮", "第二轮", "第三轮", "第四轮");
    }

    @Test
    void historyCharacterLimitDoesNotDefineTheSemanticWindow() {
        AssistantRequestGuard narrow = new AssistantRequestGuard(
                new AssistantConfig(false, "", "", "gpt-4o-mini", 30, 100, 8, 10, 600, 3));

        List<AssistantMessage> kept = narrow.normalise(List.of(
                AssistantMessage.user("12345678"),
                AssistantMessage.assistant("abcdefgh")), "现在这句").history();

        assertThat(kept).extracting(AssistantMessage::text)
                .containsExactly("12345678", "abcdefgh");
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

    /**
     * 裁剪必须被数出来。这是整套「不许静默」立场的落点：模型不会察觉自己少了语境，
     * 所以只有把「丢了多少」传到响应里，用户才有可能知道助手已经看不见前面了。
     */
    @Test
    void theNumberOfDroppedHistoryEntriesIsReported() {
        List<AssistantMessage> history = List.of(
                AssistantMessage.user("第一轮"),
                AssistantMessage.assistant("第二轮"),
                AssistantMessage.user("第三轮"),
                AssistantMessage.assistant("第四轮"));

        AssistantRequestGuard.NormalisedRequest normalised = guard.normalise(history, "现在这句");

        assertThat(normalised.history()).hasSize(4);
        assertThat(normalised.droppedHistoryMessages()).isZero();
    }

    @Test
    void nothingIsReportedAsDroppedWhenEverythingFits() {
        AssistantRequestGuard.NormalisedRequest normalised =
                guard.normalise(List.of(AssistantMessage.user("只有一轮")), "现在这句");

        assertThat(normalised.droppedHistoryMessages()).isZero();
    }

    /**
     * 空消息不能计入分母。它们本来就没有内容，算进去会让「丢了 3 条」这个数字不可信 ——
     * 而一个不可信的数字比不显示更糟：用户会按它去判断助手到底记得多少。
     */
    @Test
    void blankHistoryEntriesAreNotCountedAsDropped() {
        List<AssistantMessage> history = new ArrayList<>();
        history.add(AssistantMessage.user("   "));
        history.add(null);
        history.add(AssistantMessage.user("有效的一轮"));

        AssistantRequestGuard.NormalisedRequest normalised = guard.normalise(history, "现在这句");

        assertThat(normalised.history()).hasSize(1);
        assertThat(normalised.droppedHistoryMessages()).isZero();
    }

    /** 旧字符预算不再决定语义窗口；两条短消息均在 token 预算内。 */
    @Test
    void aHistoryWithinTokenBudgetIsNotDroppedByCharacterLimit() {
        AssistantRequestGuard narrow = new AssistantRequestGuard(
                new AssistantConfig(false, "", "", "gpt-4o-mini", 30, 100, 8, 10, 600, 3));

        AssistantRequestGuard.NormalisedRequest normalised = narrow.normalise(List.of(
                AssistantMessage.user("12345678"),
                AssistantMessage.assistant("abcdefgh")), "现在这句");

        assertThat(normalised.history()).hasSize(2);
        assertThat(normalised.droppedHistoryMessages()).isZero();
    }

    @Test
    void historyUsesTokenBudgetInsteadOfConfiguredCharacterBudget() {
        AssistantRequestGuard narrowCharacters = new AssistantRequestGuard(
                new AssistantConfig(false, "", "", "gpt-4o-mini", 30, 100, 8, 10, 600, 3));

        AssistantRequestGuard.NormalisedRequest normalised = narrowCharacters.normalise(List.of(
                AssistantMessage.user("中文消息一"),
                AssistantMessage.assistant("中文消息二")), "现在这句");

        assertThat(normalised.history()).extracting(AssistantMessage::text)
                .containsExactly("中文消息一", "中文消息二");
        assertThat(normalised.droppedHistoryMessages()).isZero();
    }

    @Test
    void historyDoesNotUseTurnCountAsSemanticWindow() {
        List<AssistantMessage> history = List.of(
                AssistantMessage.user("一"), AssistantMessage.assistant("二"),
                AssistantMessage.user("三"), AssistantMessage.assistant("四"),
                AssistantMessage.user("五"), AssistantMessage.assistant("六"),
                AssistantMessage.user("七"), AssistantMessage.assistant("八"),
                AssistantMessage.user("九"));

        AssistantRequestGuard.NormalisedRequest normalised = guard.normalise(history, "现在这句");

        assertThat(normalised.history()).hasSize(9);
        assertThat(normalised.droppedHistoryMessages()).isZero();
    }
}
