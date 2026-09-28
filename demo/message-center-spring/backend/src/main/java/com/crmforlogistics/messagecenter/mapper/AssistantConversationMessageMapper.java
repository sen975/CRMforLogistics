package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.AssistantConversationMessageEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 助手会话消息。只有「追加一条」与「读回来」两件事 ——
 * 这是一张追加写表，不提供更新与删除：能改的对话记录不能用来复盘。
 *
 * <h2>三个读方法，三种问法</h2>
 * <ul>
 *   <li>{@link #listByConversation} 从**头**读：面板回放要完整对话。</li>
 *   <li>{@link #listRecentByConversation} 从**尾**读：装配下一轮语境要刚说过的话。</li>
 *   <li>{@link #latestConversationId} 不限定会话：要回答「我上次在哪个会话里」。</li>
 * </ul>
 * 前两者的差别只有方向，而那个差别是本质的：短会话里两者结果相同，长会话里差别是决定性的
 * —— 决定模型看到的是「刚说过的话」还是「对话开头」。所以它们不能合并成一个方法。
 */
@Mapper
public interface AssistantConversationMessageMapper {

    /**
     * 归属谓词里与「哪个会话」无关的那一半：**这个人是谁**。
     *
     * <p>单独抽出来是因为现在有两种形状的读方法 —— 限定在某个会话里的
     * （{@link #WHERE_OWNED_BY_USER}）与跨会话找用户自己的（{@link #latestConversationId}）。
     * 两者只有这一半谓词相同，而这一半恰恰是最不能写错的一半。
     */
    String OWNED_BY_USER_ID = "user_id = #{userId}::uuid ";

    /**
     * 归属谓词只有这一份，两个会话内读方法共用。
     *
     * <p>{@code user_id} 是查询条件的一部分而不是事后过滤：跨用户的会话即使
     * {@code conversation_id} 撞上（UUID 碰撞或有人拿别人的号来试），也读不出任何东西。
     * 归属校验放在 SQL 里是这套系统的既有立场 —— 不依赖调用方记得校验。
     *
     * <p>抽成常量而不是各写一遍，是为了让「写第三个读方法时漏掉 user_id」在结构上不可能发生：
     * 那是这套系统里最严重的一类错误，而它在 code review 里与正常代码**长得一模一样**。
     */
    String WHERE_OWNED_BY_USER =
            "where " + OWNED_BY_USER_ID + "and conversation_id = #{conversationId}::uuid ";

    /** {@code id} 与 {@code created_at} 都走数据库默认值，写入方不需要（也不该）自己造时间。 */
    @Insert("insert into assistant_conversation_messages "
            + "(conversation_id, user_id, role, kind, text) "
            + "values (#{conversationId}::uuid, #{userId}::uuid, #{role}, #{kind}, #{text})")
    int insert(AssistantConversationMessageEntity entity);

    /**
     * 某会话的历史消息，<b>时间正序</b>（对话要从上往下读）。
     *
     * <p>取的是**最旧**的 {@code limit} 条 —— 面板回放要的是完整对话，从开头开始。
     */
    @Select("select id, conversation_id, user_id, role, kind, text, created_at "
            + "from assistant_conversation_messages "
            + WHERE_OWNED_BY_USER
            + "order by created_at asc, id asc limit #{limit}")
    List<AssistantConversationMessageEntity> listByConversation(@Param("userId") UUID userId,
                                                               @Param("conversationId") UUID conversationId,
                                                               @Param("limit") int limit);

    /**
     * 某会话**最近**的 {@code limit} 条，用于装配下一轮请求的会话语境。
     *
     * <p><b>不要用 {@link #listByConversation} 代替</b>：那个方法带 {@code asc}，返回的是
     * 这段对话**最早**的 N 条。短会话里两者一样，长会话里它会把「刚说过的话」全部丢掉、
     * 留下对话开头 —— 而指代（「那条」「改成后天」）恰恰指向刚说过的话。
     *
     * <p>返回顺序是**倒序**（最新在前），调用方要正序时自行反转。把反转留给调用方，
     * 是为了让「这里读的是最近 N 条」这件事在 SQL 上就能看出来 ——
     * 若这里直接正序返回，两种语义在读代码时就不可区分了。
     */
    @Select("select id, conversation_id, user_id, role, kind, text, created_at "
            + "from assistant_conversation_messages "
            + WHERE_OWNED_BY_USER
            + "order by created_at desc, id desc limit #{limit}")
    List<AssistantConversationMessageEntity> listRecentByConversation(@Param("userId") UUID userId,
                                                                     @Param("conversationId") UUID conversationId,
                                                                     @Param("limit") int limit);

    /**
     * Bounded ascending keyset page after a summary cursor. Null cursor starts from the conversation beginning.
     */
    @Select("select id, conversation_id, user_id, role, kind, text, created_at "
            + "from assistant_conversation_messages "
            + WHERE_OWNED_BY_USER
            + "and (#{afterCreatedAt}::timestamptz is null "
            + "or created_at > #{afterCreatedAt} "
            + "or (created_at = #{afterCreatedAt} and id > #{afterMessageId}::uuid)) "
            + "order by created_at asc, id asc limit #{limit}")
    List<AssistantConversationMessageEntity> listAfter(@Param("userId") UUID userId,
                                                       @Param("conversationId") UUID conversationId,
                                                       @Param("afterCreatedAt") java.time.Instant afterCreatedAt,
                                                       @Param("afterMessageId") UUID afterMessageId,
                                                       @Param("limit") int limit);

    @Select("select count(*) from assistant_conversation_messages "
            + WHERE_OWNED_BY_USER
            + "and (created_at < #{beforeCreatedAt} or "
            + "(created_at = #{beforeCreatedAt} and id < #{beforeMessageId}::uuid))")
    int countBefore(@Param("userId") UUID userId,
                    @Param("conversationId") UUID conversationId,
                    @Param("beforeCreatedAt") java.time.Instant beforeCreatedAt,
                    @Param("beforeMessageId") UUID beforeMessageId);

    /**
     * 这个用户**最近说过话**的那个会话号，一行都没有时返回 {@code null}。
     *
     * <p>它是三种读法里唯一不限定会话的：问题不是「这个会话里有什么」，而是「我上次在哪个会话里」。
     * 排序键与 {@link #listRecentByConversation} 完全一致（{@code created_at desc, id desc}）——
     * 两处若不一致，「交回给你的会话号」与「按那个号读回来的内容」会指向不同的会话。
     *
     * <p>{@code id} 参与排序不只是为了并列时确定：同一毫秒内写入的多行（一轮对话的用户行与
     * 助手行）靠它稳定排序，否则「最近一条」在同一次查询里可能给出两种答案。
     *
     * <p>注意 {@code role} 不加过滤：用户「说过话」的会话，意味着这张表里至少有一行属于它，
     * 而这张表的每一行都是这一轮对话的一部分。按 {@code role='user'} 过滤只会额外要求
     * 「用户行已落库」，那在助手行已落库时并不必然成立（写入是两条独立语句）。
     */
    @Select("select conversation_id from assistant_conversation_messages "
            + "where " + OWNED_BY_USER_ID
            + "order by created_at desc, id desc limit 1")
    UUID latestConversationId(@Param("userId") UUID userId);
}
