package com.crmforlogistics.messagecenter.service.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息候选集：形状契约与「前缀必须挡住别的域」这两件事。
 *
 * <p>前缀比对不是洁癖：{@code CONTACT:<uuid>} 与 {@code MESSAGE:<uuid>} 的 uuid 部分
 * 完全可能是同一个值（本地联调时就出现过），所以「拿一个联系人 ref 去读消息」
 * 只能靠前缀拦住 —— 只判 uuid 能不能解析，等于放它过去。
 */
class MessageCandidatesTest {

    private static final UUID MESSAGE_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");

    @Test
    void anIdRoundTripsBackToTheSameMessageId() {
        String id = MessageCandidates.idOf(MESSAGE_ID);

        assertThat(id).isEqualTo("MESSAGE:" + MESSAGE_ID);
        assertThat(MessageCandidates.targetOf(id)).isEqualTo(MESSAGE_ID);
    }

    @Test
    void aReferenceFromAnotherDomainIsNotAMessageReference() {
        assertThat(MessageCandidates.targetOf(ConversationCandidates.Item.idOf(
                ConversationCandidates.TYPE_CONTACT, MESSAGE_ID)))
                .as("uuid 一样也不行 —— 域名不同就不是同一种引用")
                .isNull();
        assertThat(MessageCandidates.targetOf(TopicCandidates.idOf(MESSAGE_ID))).isNull();
    }

    @Test
    void malformedReferencesReturnNullInsteadOfThrowing() {
        // 返回 null 而不是抛：这不是"服务端写错了"，而是"模型的参数不对"，
        // 两者的处置完全不同（前者起不来，后者是 INVALID_ARGUMENT）。
        assertThat(MessageCandidates.targetOf(null)).isNull();
        assertThat(MessageCandidates.targetOf("")).isNull();
        assertThat(MessageCandidates.targetOf("MESSAGE:")).isNull();
        assertThat(MessageCandidates.targetOf("MESSAGE:not-a-uuid")).isNull();
        assertThat(MessageCandidates.targetOf(":99999999-9999-4999-8999-999999999999")).isNull();
    }

    @Test
    void containsComparesTheWholeReferenceNotTheSuffix() {
        MessageCandidates candidates = new MessageCandidates(MessageCandidates.LIMIT, List.of(
                new MessageCandidates.Item(MessageCandidates.idOf(MESSAGE_ID), "inbound", "delivered",
                        "2026-09-20T10:00:00Z")));

        assertThat(candidates.contains(MessageCandidates.idOf(MESSAGE_ID))).isTrue();
        assertThat(candidates.contains(MESSAGE_ID.toString())).isFalse();
        assertThat(candidates.contains(ConversationCandidates.Item.idOf(
                ConversationCandidates.TYPE_CONTACT, MESSAGE_ID))).isFalse();
        assertThat(candidates.contains(null)).isFalse();
    }
}
