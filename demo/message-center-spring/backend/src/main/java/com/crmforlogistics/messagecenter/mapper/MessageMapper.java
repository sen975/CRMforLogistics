package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
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
    int insertWithSequence(MessageEntity message);

    /**
     * List messages for a conversation with cursor-based pagination.
     * Authorization: user must be assigned to the conversation, on the team,
     * or have an active access grant.
     */
    IPage<MessageEntity> listMessages(IPage<MessageEntity> page,
                                      @Param("conversationId") UUID conversationId,
                                      @Param("userId") UUID userId,
                                      @Param("beforeCursor") Instant beforeCursor,
                                      @Param("beforeId") UUID beforeId);

    /**
     * Find a message by provider_message_id for the given channel account.
     */
    Optional<MessageEntity> findByProviderMessageId(@Param("channelAccountId") UUID channelAccountId,
                                                    @Param("providerMessageId") String providerMessageId);

    /**
     * Find a message by client_request_id for the given channel account (idempotency check).
     */
    Optional<MessageEntity> findByClientRequestId(@Param("channelAccountId") UUID channelAccountId,
                                                  @Param("clientRequestId") String clientRequestId);
}
