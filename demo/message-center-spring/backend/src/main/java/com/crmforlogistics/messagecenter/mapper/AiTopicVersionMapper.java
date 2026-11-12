package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.AiTopicVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiTopicVersionMapper extends BaseMapper<AiTopicVersionEntity> {
    @Insert("insert into ai_topic_versions (id, topic_id, version, change_type, title, summary, source_topic_ids, actor_user_id) values (gen_random_uuid(), #{topicId}::uuid, #{version}, #{changeType}, #{title}, #{summary}, coalesce(cast(#{sourceTopicIds} as jsonb), '[]'::jsonb), #{actorUserId}::uuid)")
    int insertVersion(@Param("topicId") java.util.UUID topicId, @Param("version") long version,
                      @Param("changeType") String changeType, @Param("title") String title,
                      @Param("summary") String summary, @Param("sourceTopicIds") String sourceTopicIds,
                      @Param("actorUserId") java.util.UUID actorUserId);
}
