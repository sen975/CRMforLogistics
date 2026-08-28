package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.UUID;

@Mapper
public interface AiTopicMapper extends BaseMapper<AiTopicEntity> {
    @Select("select t.* from ai_topics t join contacts c on c.id=t.contact_id where t.contact_id=#{contactId}::uuid and t.status='READY' and c.deleted_at is null order by t.last_occurred_at desc, t.id")
    List<AiTopicEntity> listReady(UUID contactId);

    @Update("update ai_topics set title=#{title}, confirmed_summary=#{confirmedSummary}, version=version+1, updated_at=now() where id=#{id}::uuid and version=#{expectedVersion} and status='READY'")
    int updateEmployee(@Param("id") UUID id, @Param("title") String title, @Param("confirmedSummary") String confirmedSummary, @Param("expectedVersion") long expectedVersion);

    @Update("update ai_topics set status='ARCHIVED', updated_at=now() where id=#{id}::uuid and status='READY'")
    int archive(@Param("id") UUID id);

    @Update("update ai_topics set status=#{status}, version=version+1, updated_at=now() where id=#{id}::uuid and status in ('READY','DISCARDED')")
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    @Select("<script>select t.* from ai_topics t join contacts c on c.id=t.contact_id where t.status='DISCARDED' and c.deleted_at is null "
            + "and (#{isAdmin}=true or c.created_by=#{userId}::uuid or exists (select 1 from contact_identities ci join conversations cv on cv.contact_identity_id=ci.id where ci.contact_id=c.id and (cv.assigned_user_id=#{userId}::uuid or exists (select 1 from team_members tm where tm.team_id=cv.assigned_team_id and tm.user_id=#{userId}::uuid) or exists (select 1 from conversation_access_grants g where g.conversation_id=cv.id and g.user_id=#{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at>now()))))) "
            + "<if test=\"search != null and search != ''\">and (t.title ilike '%'||#{search}||'%' or t.ai_summary ilike '%'||#{search}||'%' or c.display_name ilike '%'||#{search}||'%')</if> "
            + "order by t.last_occurred_at desc, t.id</script>")
    IPage<AiTopicEntity> listDiscardedForUser(Page<AiTopicEntity> page, @Param("userId") UUID userId,
                                               @Param("search") String search, @Param("isAdmin") boolean isAdmin);
}
