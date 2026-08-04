package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ContactMapper extends BaseMapper<ContactEntity> {

    /**
     * List contacts visible to a user with permission scoping:
     * user-owned contacts OR contacts with team-assigned conversations
     * OR contacts with access grants, excluding soft-deleted and merged contacts.
     * Cursor pagination by sort_at desc, id desc.
     */
    IPage<ContactEntity> listForUser(IPage<ContactEntity> page,
                                     @Param("userId") UUID userId,
                                     @Param("search") String search,
                                     @Param("beforeLastMessageAt") Instant beforeLastMessageAt,
                                     @Param("beforeId") UUID beforeId);

    Optional<ContactEntity> findById(@Param("id") UUID id);
}
