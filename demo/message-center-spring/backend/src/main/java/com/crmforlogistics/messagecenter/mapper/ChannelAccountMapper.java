package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ChannelAccountMapper extends BaseMapper<ChannelAccountEntity> {

    @Select("select id, owner_user_id, channel_type, name, account_identifier, " +
            "account_identifier_normalized, auth_status, sync_status, encrypted_config, " +
            "last_synced_at, created_at, updated_at, deleted_at, version " +
            "from channel_accounts where id = #{id}::uuid and owner_user_id = #{ownerId}::uuid " +
            "and deleted_at is null limit 1")
    ChannelAccountEntity findByIdAndOwner(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    @Select("select id, owner_user_id, channel_type, name, account_identifier, " +
            "account_identifier_normalized, auth_status, sync_status, encrypted_config, " +
            "last_synced_at, created_at, updated_at, deleted_at, version " +
            "from channel_accounts where owner_user_id = #{ownerId}::uuid " +
            "and channel_type = #{channelType} and deleted_at is null " +
            "and auth_status in ('active', 'expired', 'failed') " +
            "order by created_at asc limit 2")
    List<ChannelAccountEntity> findByOwnerAndChannelType(@Param("ownerId") UUID ownerId,
                                                         @Param("channelType") String channelType);

    @Insert("insert into channel_accounts (id, channel_type, name, account_identifier, " +
            "account_identifier_normalized, auth_status, sync_status, encrypted_config) " +
            "values (#{id}::uuid, 'wecom', #{name}, #{authCorpId}, #{authCorpId}, " +
            "'active', 'idle', '{}'::jsonb) " +
            "on conflict (channel_type, account_identifier_normalized) where deleted_at is null " +
            "do update set auth_status = 'active', updated_at = now(), " +
            "version = channel_accounts.version + 1")
    int upsertWeComAccount(@Param("id") UUID id,
                           @Param("authCorpId") String authCorpId,
                           @Param("name") String name);

    @Select("select id, channel_type, name, account_identifier, account_identifier_normalized, " +
            "auth_status, sync_status, encrypted_config, last_synced_at, created_at, updated_at, " +
            "deleted_at, version from channel_accounts where channel_type = 'wecom' " +
            "and account_identifier_normalized = #{authCorpId} and auth_status = 'active' " +
            "and deleted_at is null limit 1")
    ChannelAccountEntity selectActiveWeComAccount(@Param("authCorpId") String authCorpId);

    @Update("update channel_accounts set auth_status = 'disabled', sync_status = 'idle', " +
            "updated_at = now(), version = version + 1 where channel_type = 'wecom' " +
            "and account_identifier_normalized = #{authCorpId} and deleted_at is null")
    int disableWeComAccount(@Param("authCorpId") String authCorpId);

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

    default List<ChannelAccountEntity> selectActiveChatAppAccounts() {
        return selectList(new LambdaQueryWrapper<ChannelAccountEntity>()
                .in(ChannelAccountEntity::getChannelType, List.of("chatapp", "whatsapp"))
                .eq(ChannelAccountEntity::getAuthStatus, "active")
                .isNull(ChannelAccountEntity::getDeletedAt)
                .last("limit 2"));
    }

    default List<ChannelAccountEntity> selectActiveChatAppAccountsForSync() {
        return selectList(new LambdaQueryWrapper<ChannelAccountEntity>()
                .in(ChannelAccountEntity::getChannelType, List.of("chatapp", "whatsapp"))
                .eq(ChannelAccountEntity::getAuthStatus, "active")
                .isNull(ChannelAccountEntity::getDeletedAt)
                .orderByAsc(ChannelAccountEntity::getCreatedAt)
                .last("limit 100"));
    }

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
