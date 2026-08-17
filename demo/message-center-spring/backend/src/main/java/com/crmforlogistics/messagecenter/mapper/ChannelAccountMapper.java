package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ChannelAccountMapper extends BaseMapper<ChannelAccountEntity> {

    @Update("UPDATE channel_accounts SET name = #{name}, updated_at = now() WHERE id = #{id}::uuid")
    int updateName(@Param("id") UUID id, @Param("name") String name);

    @Update("UPDATE channel_accounts SET account_identifier = #{accountIdentifier}, account_identifier_normalized = #{accountIdentifier}, updated_at = now() WHERE id = #{id}::uuid")
    int updateAccountIdentifier(@Param("id") UUID id, @Param("accountIdentifier") String accountIdentifier);

    @Update("UPDATE channel_accounts SET name = #{name}, account_identifier = #{accountIdentifier}, account_identifier_normalized = #{accountIdentifier}, updated_at = now() WHERE id = #{id}::uuid")
    int updateNameAndIdentifier(@Param("id") UUID id, @Param("name") String name, @Param("accountIdentifier") String accountIdentifier);

    @Update("UPDATE channel_accounts SET encrypted_config = #{config}::jsonb, updated_at = now() WHERE id = #{id}::uuid")
    int updateEncryptedConfig(@Param("id") UUID id, @Param("config") String config);

    @Update("UPDATE channel_accounts SET sync_status = #{status}, last_synced_at = #{lastSyncedAt}, updated_at = now() WHERE id = #{id}::uuid")
    int updateSyncStatus(@Param("id") UUID id, @Param("status") String status, @Param("lastSyncedAt") java.time.Instant lastSyncedAt);

    default ChannelAccountEntity selectSingleActiveByChannelType(String channelType) {
        List<ChannelAccountEntity> accounts = selectList(new LambdaQueryWrapper<ChannelAccountEntity>()
                .eq(ChannelAccountEntity::getChannelType, channelType)
                .eq(ChannelAccountEntity::getAuthStatus, "active")
                .isNull(ChannelAccountEntity::getDeletedAt)
                .orderByAsc(ChannelAccountEntity::getCreatedAt)
                .last("limit 2"));
        if (accounts.size() != 1) {
            throw new IllegalStateException("Exactly one active " + channelType + " channel account is required");
        }
        return accounts.get(0);
    }
}
