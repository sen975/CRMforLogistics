package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.CallTranscriptRevisionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface CallTranscriptRevisionMapper extends BaseMapper<CallTranscriptRevisionEntity> {

    @Select("SELECT * FROM call_transcript_revisions WHERE call_record_id = #{callRecordId}::uuid ORDER BY edited_at ASC")
    List<CallTranscriptRevisionEntity> listByCallRecordId(@Param("callRecordId") UUID callRecordId);
}
