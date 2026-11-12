package com.crmforlogistics.messagecenter.service.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 联系人候选集：id 形状、比对、以及「群不是人」这条边界。
 *
 * <p>这个类小，但它钉住的两件事都是别处悄悄坏掉的类型：
 *
 * <ul>
 *   <li><b>{@code contains} 是「模型编造 id」的第一道拦截</b>（第二道是 SQL 里的
 *       {@code where user_id}）。它一旦对任意 id 返回 {@code true}，防线就没了，
 *       而功能<b>看起来完全正常</b>。</li>
 *   <li><b>ref 的形状是两域共享的</b>：{@code CONTACT:<uuid>} 既是会话候选也是联系人候选。
 *       因此「接受企微群 ref」这种错误很容易顺手写出来 —— 那会让
 *       {@code contact.brief} 收到一个群 id 才在运行时失败。</li>
 * </ul>
 */
class ContactCandidatesTest {

    private static final UUID CONTACT = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID GROUP = UUID.fromString("77777777-7777-4777-8777-777777777777");

    private static final String CONTACT_REF = ContactCandidates.idOf(CONTACT);

    private final ContactCandidates candidates = new ContactCandidates(20, List.of(
            new ContactCandidates.Item(CONTACT_REF, "周明", "张江物流 对接人"),
            new ContactCandidates.Item(ContactCandidates.idOf(GROUP), "另一个联系人", null)));

    /**
     * 联系人 ref 与会话 ref 的拼装必须逐字节一致 —— 它们指向同一张 {@code contacts} 表。
     *
     * <p>这条断言存在的意义不是「格式好看」，而是：两域各写一份拼装就会漂移，
     * 漂移之后 {@code conversation.search} 找到的人 {@code contact.brief} 就不认了，
     * 而那种失败看起来像「模型挑错了」。
     */
    @Test
    void theReferenceUsesTheSameCompositeFormatAsConversations() {
        assertThat(CONTACT_REF)
                .isEqualTo(ConversationCandidates.Item.idOf(ConversationCandidates.TYPE_CONTACT, CONTACT));
        assertThat(CONTACT_REF).isEqualTo("CONTACT:" + CONTACT);
        assertThat(ContactCandidates.targetOf(CONTACT_REF)).isEqualTo(CONTACT);
    }

    @Test
    void containsOnlyAcceptsIdsThatAreInTheWindow() {
        assertThat(candidates.contains(CONTACT_REF)).isTrue();
        assertThat(candidates.contains("CONTACT:" + UUID.randomUUID())).isFalse();
        assertThat(candidates.contains(null)).isFalse();
    }

    /** 企微群的 ref 不是联系人。它必须在这里就被挡下，而不是等到运行时才发现类型不对。 */
    @Test
    void aGroupReferenceIsNotAContactReference() {
        String groupRef = ConversationCandidates.Item.idOf(ConversationCandidates.TYPE_WECOM_GROUP, GROUP);

        assertThat(ContactCandidates.targetOf(groupRef)).isNull();
        assertThat(candidates.contains(groupRef)).isFalse();
    }

    @Test
    void malformedReferencesYieldNullInsteadOfThrowing() {
        // 返回 null 而不是抛：这不是「服务端写错了」，是「模型的参数不对」，
        // 两者对用户的指引不同，收口在工具层转成参数错误。
        assertThat(ContactCandidates.targetOf(null)).isNull();
        assertThat(ContactCandidates.targetOf("")).isNull();
        assertThat(ContactCandidates.targetOf("周明")).isNull();
        assertThat(ContactCandidates.targetOf("CONTACT:not-a-uuid")).isNull();
        assertThat(ContactCandidates.targetOf(":" + CONTACT)).isNull();
    }

    @Test
    void itemsAreDefensivelyCopiedAndNullsBecomeAnEmptyList() {
        assertThat(new ContactCandidates(20, null).items()).isEmpty();
        assertThat(candidates.items()).hasSize(2);
    }
}
