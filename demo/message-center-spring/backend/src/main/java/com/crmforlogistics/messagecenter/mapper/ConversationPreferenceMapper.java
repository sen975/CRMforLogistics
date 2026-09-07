package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.ConversationPreferenceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface ConversationPreferenceMapper {
    @Select("select user_id, target_type, target_id, pinned, hidden_at, sort_rank, created_at, updated_at "
            + "from conversation_preferences where user_id = #{userId}::uuid and target_type = #{targetType} "
            + "and target_id = #{targetId}::uuid")
    ConversationPreferenceEntity find(@Param("userId") UUID userId,
                                      @Param("targetType") String targetType,
                                      @Param("targetId") UUID targetId);

    @Insert("insert into conversation_preferences (user_id, target_type, target_id) "
            + "values (#{userId}::uuid, #{targetType}, #{targetId}::uuid) on conflict do nothing")
    int ensure(@Param("userId") UUID userId, @Param("targetType") String targetType,
               @Param("targetId") UUID targetId);

    @Update("update conversation_preferences set pinned = #{pinned}, updated_at = now() "
            + "where user_id = #{userId}::uuid and target_type = #{targetType} and target_id = #{targetId}::uuid")
    int setPinned(@Param("userId") UUID userId, @Param("targetType") String targetType,
                  @Param("targetId") UUID targetId, @Param("pinned") boolean pinned);

    @Update("update conversation_preferences set hidden_at = now(), updated_at = now() "
            + "where user_id = #{userId}::uuid and target_type = #{targetType} and target_id = #{targetId}::uuid")
    int setHiddenNow(@Param("userId") UUID userId, @Param("targetType") String targetType,
                     @Param("targetId") UUID targetId);

    @Select("select 1 from pg_advisory_xact_lock(hashtextextended(#{userId}::text, 0))")
    Integer lockForUser(@Param("userId") UUID userId);

    @Update("update conversation_preferences set sort_rank = null, updated_at = now() "
            + "where user_id = #{userId}::uuid and sort_rank is not null")
    int clearSortRanks(@Param("userId") UUID userId);

    @Update("update conversation_preferences set sort_rank = #{sortRank}, updated_at = now() "
            + "where user_id = #{userId}::uuid and target_type = #{targetType} and target_id = #{targetId}::uuid")
    int setSortRank(@Param("userId") UUID userId, @Param("targetType") String targetType,
                    @Param("targetId") UUID targetId, @Param("sortRank") long sortRank);
}
