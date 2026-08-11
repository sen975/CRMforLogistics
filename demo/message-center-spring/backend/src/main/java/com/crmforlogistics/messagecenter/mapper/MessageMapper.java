package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {

    /**
     * Atomically insert a message with ingest_sequence incremented from
     * the conversation. Uses a CTE: UPDATE conversations SET next_ingest_sequence+1
     * RETURNING next_ingest_sequence, then INSERT using that value.
     * Sets the ingest_sequence field on the entity after insert.
     */
    @Insert("with seq as (" +
        "update conversations set next_ingest_sequence = next_ingest_sequence + 1, version = version + 1 " +
        "where id = #{conversationId}::uuid returning next_ingest_sequence" +
        ") insert into messages (id, conversation_id, channel_account_id, source_event_id, provider_message_id, client_request_id, direction, message_kind, subject, body_text, body_html, occurred_at, ingest_sequence, counts_as_unread, current_status, current_status_at, created_by_user_id, metadata_jsonb) " +
        "select #{id}::uuid, #{conversationId}::uuid, #{channelAccountId}::uuid, #{sourceEventId}::uuid, #{providerMessageId}, #{clientRequestId}, " +
        "#{direction}, #{messageKind}, #{subject}, #{bodyText}, #{bodyHtml}, #{occurredAt}, seq.next_ingest_sequence, #{countsAsUnread}, #{currentStatus}, #{currentStatusAt}, #{createdByUserId}::uuid, " +
        "coalesce(cast(#{metadataJsonb} as jsonb), '{}'::jsonb) from seq")
    int insertWithSequence(MessageEntity message);

    /**
     * List messages for a conversation with cursor-based pagination.
     * Authorization: user must be assigned to the conversation, on the team,
     * or have an active access grant.
     */
    @Select("<script>" +
        "select m.id, m.provider_message_id, m.channel_account_id, m.conversation_id, " +
        "m.source_event_id, m.client_request_id, m.direction, m.message_kind, " +
        "m.subject, m.body_text, m.body_html, m.occurred_at, m.received_at, " +
        "m.ingest_sequence, m.counts_as_unread, m.current_status, " +
        "m.current_status_at, m.created_by_user_id, m.metadata_jsonb, m.created_at " +
        "from messages m " +
        "join conversations cv on cv.id = m.conversation_id " +
        "where m.conversation_id = #{conversationId}::uuid " +
        "<if test=\"!isAdmin\">" +
        "and (cv.assigned_user_id = #{userId}::uuid " +
        "  or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id and tm.user_id = #{userId}::uuid) " +
        "  or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id and g.user_id = #{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at > now())) " +
        ") " +
        "</if>" +
        "<if test=\"beforeCursor != null and beforeId != null\">" +
        "  and (m.occurred_at &lt; #{beforeCursor} or (m.occurred_at = #{beforeCursor} and m.id &lt; #{beforeId}::uuid)) " +
        "</if>" +
        "order by m.occurred_at desc, m.id desc " +
        "limit ${page.size}" +
        "</script>")
    IPage<MessageEntity> listMessages(IPage<MessageEntity> page,
                                      @Param("conversationId") UUID conversationId,
                                      @Param("userId") UUID userId,
                                      @Param("beforeCursor") Instant beforeCursor,
                                      @Param("beforeId") UUID beforeId,
                                      @Param("isAdmin") boolean isAdmin);

    /**
     * Find a message by provider_message_id for the given channel account.
     */
    @Select("select id, provider_message_id, channel_account_id, conversation_id, " +
        "source_event_id, client_request_id, direction, message_kind, " +
        "subject, body_text, body_html, occurred_at, received_at, " +
        "ingest_sequence, counts_as_unread, current_status, " +
        "current_status_at, created_by_user_id, metadata_jsonb, created_at " +
        "from messages " +
        "where channel_account_id = #{channelAccountId}::uuid " +
        "and provider_message_id = #{providerMessageId} " +
        "limit 1")
    Optional<MessageEntity> findByProviderMessageId(@Param("channelAccountId") UUID channelAccountId,
                                                    @Param("providerMessageId") String providerMessageId);

    /**
     * Find a message by client_request_id for the given channel account (idempotency check).
     */
    @Select("select id, provider_message_id, channel_account_id, conversation_id, " +
        "source_event_id, client_request_id, direction, message_kind, " +
        "subject, body_text, body_html, occurred_at, received_at, " +
        "ingest_sequence, counts_as_unread, current_status, " +
        "current_status_at, created_by_user_id, metadata_jsonb, created_at " +
        "from messages " +
        "where channel_account_id = #{channelAccountId}::uuid " +
        "and client_request_id = #{clientRequestId} " +
        "limit 1")
    Optional<MessageEntity> findByClientRequestId(@Param("channelAccountId") UUID channelAccountId,
                                                  @Param("clientRequestId") String clientRequestId);

    @Update("""
            update messages
            set provider_message_id = coalesce(#{providerMessageId}, provider_message_id),
                current_status = #{status},
                current_status_at = #{statusAt}
            where id = #{messageId}::uuid
            """)
    int updateDeliveryStatus(@Param("messageId") UUID messageId,
                             @Param("providerMessageId") String providerMessageId,
                             @Param("status") String status,
                             @Param("statusAt") Instant statusAt);

    @Update("""
            update messages
            set provider_message_id = #{providerMessageId}
            where id = #{messageId}::uuid
            """)
    int updateProviderMessageId(@Param("messageId") UUID messageId,
                                @Param("providerMessageId") String providerMessageId);

    @Update("""
            update messages
            set current_status = 'submission_unknown',
                current_status_at = #{statusAt}
            where id = #{messageId}::uuid
              and current_status in ('pending', 'processing')
            """)
    int markSubmissionUnknownIfUnresolved(@Param("messageId") UUID messageId,
                                          @Param("statusAt") Instant statusAt);

    /**
     * List messages across multiple conversations with cursor-based pagination.
     */
    @Select("<script>" +
        "select m.id, m.provider_message_id, m.channel_account_id, m.conversation_id, " +
        "m.source_event_id, m.client_request_id, m.direction, m.message_kind, " +
        "m.subject, m.body_text, m.body_html, m.occurred_at, m.received_at, " +
        "m.ingest_sequence, m.counts_as_unread, m.current_status, " +
        "m.current_status_at, m.created_by_user_id, m.metadata_jsonb, m.created_at " +
        "from messages m " +
        "join conversations cv on cv.id = m.conversation_id " +
        "where m.conversation_id in " +
        "<foreach item='cid' collection='conversationIds' open='(' separator=',' close=')'>" +
        "#{cid}::uuid" +
        "</foreach>" +
        "<if test=\"!isAdmin\">" +
        "and (cv.assigned_user_id = #{userId}::uuid " +
        "  or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id and tm.user_id = #{userId}::uuid) " +
        "  or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id and g.user_id = #{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at > now())) " +
        ") " +
        "</if>" +
        "<if test=\"beforeCursor != null and beforeId != null\">" +
        "  and (m.occurred_at &lt; #{beforeCursor} or (m.occurred_at = #{beforeCursor} and m.id &lt; #{beforeId}::uuid)) " +
        "</if>" +
        "order by m.occurred_at desc, m.id desc " +
        "limit ${page.size}" +
        "</script>")
    IPage<MessageEntity> listMessagesByConversations(IPage<MessageEntity> page,
                                                      @Param("conversationIds") List<UUID> conversationIds,
                                                      @Param("userId") UUID userId,
                                                      @Param("beforeCursor") Instant beforeCursor,
                                                      @Param("beforeId") UUID beforeId,
                                                      @Param("isAdmin") boolean isAdmin);

    /**
     * Mark all messages in the given conversations as read (counts_as_unread = false).
     */
    @Update("<script>" +
        "update messages set counts_as_unread = false " +
        "where conversation_id in " +
        "<foreach item='cid' collection='conversationIds' open='(' separator=',' close=')'>" +
        "#{cid}::uuid" +
        "</foreach>" +
        "</script>")
    int markRead(@Param("conversationIds") List<UUID> conversationIds);
}
