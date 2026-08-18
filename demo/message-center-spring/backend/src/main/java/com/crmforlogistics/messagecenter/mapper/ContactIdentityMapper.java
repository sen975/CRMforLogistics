package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ContactIdentityMapper extends BaseMapper<ContactIdentityEntity> {

    /**
     * Move all identities from one contact to another (merge).
     */
    @Update("update contact_identities set contact_id = #{newContactId}::uuid, updated_at = now(), version = version + 1 where contact_id = #{oldContactId}::uuid")
    int updateContactId(@Param("newContactId") UUID newContactId,
                        @Param("oldContactId") UUID oldContactId);

    /**
     * Move a single identity to a different contact (split).
     */
    @Update("update contact_identities set contact_id = #{newContactId}::uuid, updated_at = now(), version = version + 1 where id = #{identityId}::uuid")
    int updateContactIdForIdentity(@Param("newContactId") UUID newContactId,
                                   @Param("identityId") UUID identityId);

    /**
     * List identities for a contact (non-deleted, primary-first ordering).
     */
    @Select("select id, contact_id, channel_type, identity_scope, identity_value, normalized_value, display_name, is_primary, verify_status, source, created_at, updated_at, deleted_at, version from contact_identities where contact_id = #{contactId}::uuid and deleted_at is null order by is_primary desc, created_at, id")
    List<ContactIdentityEntity> findByContactId(@Param("contactId") UUID contactId);

    /**
     * Find a non-deleted identity by normalized value within a channel type.
     */
    @Select("select id, contact_id, channel_type, identity_scope, identity_value, normalized_value, display_name, is_primary, verify_status, source, created_at, updated_at, deleted_at, version from contact_identities where channel_type = #{channelType} and normalized_value = #{normalizedValue} and deleted_at is null limit 1")
    Optional<ContactIdentityEntity> findByNormalizedValue(@Param("channelType") String channelType,
                                                          @Param("normalizedValue") String normalizedValue);

    /**
     * Find a non-deleted identity by normalized value within a channel type and scope.
     */
    @Select("select id, contact_id, channel_type, identity_scope, identity_value, normalized_value, display_name, is_primary, verify_status, source, created_at, updated_at, deleted_at, version from contact_identities where channel_type = #{channelType} and identity_scope = #{identityScope} and normalized_value = #{normalizedValue} and deleted_at is null limit 1")
    Optional<ContactIdentityEntity> findByNormalizedValueInScope(@Param("channelType") String channelType,
                                                                 @Param("identityScope") String identityScope,
                                                                 @Param("normalizedValue") String normalizedValue);
}
