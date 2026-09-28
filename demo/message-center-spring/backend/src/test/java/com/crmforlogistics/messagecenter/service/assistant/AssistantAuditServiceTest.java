package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.entity.AssistantActionAuditEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantActionAuditMapper;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 审计：字段映射、规范化摘要、以及「写失败不能让用户看到假的失败」。 */
class AssistantAuditServiceTest {

    private final AssistantActionAuditMapper mapper = mock(AssistantActionAuditMapper.class);
    private final AssistantAuditService service =
            new AssistantAuditService(mapper, AssistantFixtures.objectMapper());

    @Test
    void auditStoresMetadataWithoutRawUtteranceOrArgumentValues() {
        UUID conversation = UUID.randomUUID();
        Map<String, Object> arguments = Map.of("to", "private@example.test", "body", "sensitive message body",
                "completed", true);

        String utterance = "给 13800138000 发消息，token=secret-value";
        service.record(new AssistantAuditService.Entry(AssistantFixtures.USER, conversation, utterance,
                "call", "message.send_email", arguments, "CONFIRM", "PENDING", null, "deepseek-chat", 812));

        ArgumentCaptor<AssistantActionAuditEntity> entity =
                ArgumentCaptor.forClass(AssistantActionAuditEntity.class);
        verify(mapper).insert(entity.capture());
        AssistantActionAuditEntity saved = entity.getValue();
        assertThat(saved.getUserId()).isEqualTo(AssistantFixtures.USER);
        assertThat(saved.getConversationId()).isEqualTo(conversation);
        assertThat(saved.getUtterance()).contains("sha256:", "chars:" + utterance.length())
                .doesNotContain("13800138000", "secret-value");
        assertThat(saved.getDecision()).isEqualTo("call");
        assertThat(saved.getToolName()).isEqualTo("message.send_email");
        assertThat(saved.getArgumentsJson()).contains("completed", "body", "to")
                .doesNotContain("private@example.test", "sensitive message body");
        assertThat(saved.getArgumentsDigest()).isEqualTo(service.digest(arguments));
        assertThat(saved.getPolicy()).isEqualTo("CONFIRM");
        assertThat(saved.getOutcome()).isEqualTo("PENDING");
        assertThat(saved.getModel()).isEqualTo("deepseek-chat");
        assertThat(saved.getLatencyMs()).isEqualTo(812);
    }

    @Test
    void aTurnWithoutArgumentsStoresNullsRatherThanAnEmptyObject() {
        service.record(new AssistantAuditService.Entry(AssistantFixtures.USER, null, "今天天气怎么样",
                "reply", null, null, null, "ANSWERED", null, "deepseek-chat", 700));

        ArgumentCaptor<AssistantActionAuditEntity> entity =
                ArgumentCaptor.forClass(AssistantActionAuditEntity.class);
        verify(mapper).insert(entity.capture());
        assertThat(entity.getValue().getArgumentsJson()).isNull();
        assertThat(entity.getValue().getArgumentsDigest()).isNull();
        assertThat(entity.getValue().getPolicy()).isNull();
    }

    @Test
    void longUtteranceHasBoundedMetadataButHashesTheWholeInput() {
        service.record(new AssistantAuditService.Entry(AssistantFixtures.USER, null, "x".repeat(3000),
                "reply", null, null, null, "ANSWERED", null, "m", 1));

        ArgumentCaptor<AssistantActionAuditEntity> entity =
                ArgumentCaptor.forClass(AssistantActionAuditEntity.class);
        verify(mapper).insert(entity.capture());
        assertThat(entity.getValue().getUtterance()).contains("chars:3000", "sha256:").hasSizeLessThan(2000);
    }

    /** 摘要必须与 map 的插入顺序无关，否则「同一个动作」会得到两个不同的 digest。 */
    @Test
    void theDigestIgnoresKeyOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("todoId", AssistantFixtures.TODO_QUOTE);
        first.put("completed", true);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("completed", true);
        second.put("todoId", AssistantFixtures.TODO_QUOTE);

        assertThat(service.digest(first)).isEqualTo(service.digest(second)).hasSize(64);
    }

    @Test
    void differentArgumentsProduceDifferentDigests() {
        assertThat(service.digest(Map.of("todoId", AssistantFixtures.TODO_QUOTE, "completed", true)))
                .isNotEqualTo(service.digest(Map.of("todoId", AssistantFixtures.TODO_QUOTE, "completed", false)));
    }

    @Test
    void noArgumentsMeansNoDigest() {
        assertThat(service.digest(null)).isNull();
        assertThat(service.digest(Map.of())).isNull();
    }

    @Test
    void argumentStructureOmitsNestedAndCollectionValues() {
        service.record(new AssistantAuditService.Entry(AssistantFixtures.USER, null, null,
                "call", "tool", Map.of("nested", Map.of("password", "hidden-secret"),
                        "recipients", List.of("private@example.test")), "AUTO", "EXECUTED", null, "m", 1));

        ArgumentCaptor<AssistantActionAuditEntity> entity =
                ArgumentCaptor.forClass(AssistantActionAuditEntity.class);
        verify(mapper).insert(entity.capture());
        assertThat(entity.getValue().getArgumentsJson()).contains("nested", "recipients")
                .doesNotContain("password", "hidden-secret", "private@example.test");
    }

    /**
     * 审计写失败不能让用户看到假的失败：工具可能已经执行成功，此时抛异常会告诉用户「失败了」，
     * 而数据其实已经改了。代价是极端情况下少一条审计行 —— 那条路径由 ERROR 日志兜住。
     */
    @Test
    void anAuditWriteFailureIsSwallowedSoTheUserVerdictStaysTrue() {
        when(mapper.insert(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("库挂了"));

        assertThatCode(() -> service.record(new AssistantAuditService.Entry(AssistantFixtures.USER, null,
                "帮我建个待办", "call", "todo.create", Map.of("title", "x", "date", "2026-09-22"),
                "AUTO", "EXECUTED", null, "m", 10)))
                .doesNotThrowAnyException();
    }

    @Test
    void listByUserClampsTheLimitIntoAUsableRange() {
        when(mapper.listByUser(any(), anyInt())).thenReturn(List.of());

        service.listByUser(AssistantFixtures.USER, 0);
        verify(mapper).listByUser(AssistantFixtures.USER, 1);

        service.listByUser(AssistantFixtures.USER, 10_000);
        verify(mapper).listByUser(AssistantFixtures.USER, 200);
    }
}
