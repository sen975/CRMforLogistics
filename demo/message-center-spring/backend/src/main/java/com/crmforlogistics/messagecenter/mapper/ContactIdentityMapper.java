package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ContactIdentityMapper extends BaseMapper<ContactIdentityEntity> {

    /**
     * Move all identities from one contact to another (merge).
     */
    int updateContactId(@Param("newContactId") UUID newContactId,
                        @Param("oldContactId") UUID oldContactId);

    /**
     * Move a single identity to a different contact (split).
     */
    int updateContactIdForIdentity(@Param("newContactId") UUID newContactId,
                                   @Param("identityId") UUID identityId);

    /**
     * List identities for a contact (non-deleted, primary-first ordering).
     */
    List<ContactIdentityEntity> findByContactId(@Param("contactId") UUID contactId);

    /**
     * Find a non-deleted identity by normalized value within a channel type.
     */
    Optional<ContactIdentityEntity> findByNormalizedValue(@Param("channelType") String channelType,
                                                          @Param("normalizedValue") String normalizedValue);
}
