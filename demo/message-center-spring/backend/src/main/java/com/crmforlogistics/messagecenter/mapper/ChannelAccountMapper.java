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

    @Select("select id, owner_user_id, channel_type, name, remark, account_identifier, " +
            "account_identifier_normalized, auth_status, sync_status, onboarding_mode, phone_verification_status, " +
            "provider_phone_status, encrypted_config, provider_scope_id, " +
            "last_synced_at, created_at, updated_at, deleted_at, version " +
            "from channel_accounts where owner_user_id = #{ownerId}::uuid " +
            "and channel_type in ('chatapp', 'email') and deleted_at is null order by created_at asc")
    List<ChannelAccountEntity> findAllByOwner(@Param("ownerId") UUID ownerId);

    @Select("select count(*) from channel_accounts where owner_user_id = #{ownerId}::uuid " +
            "and channel_type = #{channelType} and auth_status in ('active', 'expired', 'failed') " +
            "and deleted_at is null")
    int countActiveByOwnerAndChannel(@Param("ownerId") UUID ownerId,
                                     @Param("channelType") String channelType);

    @Select("select id, owner_user_id, channel_type, name, remark, account_identifier, " +
            "account_identifier_normalized, auth_status, sync_status, onboarding_mode, phone_verification_status, " +
            "provider_phone_status, encrypted_config, provider_scope_id, " +
            "last_synced_at, created_at, updated_at, deleted_at, version " +
            "from channel_accounts where owner_user_id = #{ownerId}::uuid " +
            "and channel_type = #{channelType} and account_identifier_normalized = #{identifier} " +
            "and deleted_at is null limit 1")
    ChannelAccountEntity findOwnedByIdentifier(@Param("ownerId") UUID ownerId,
                                                @Param("channelType") String channelType,
                                                @Param("identifier") String identifier);

    @Insert("insert into channel_accounts (id, owner_user_id, channel_type, name, remark, account_identifier, " +
            "account_identifier_normalized, auth_status, sync_status, onboarding_mode, " +
            "phone_verification_status, provider_phone_status, encrypted_config, provider_scope_id) " +
            "values (#{entity.id}::uuid, #{ownerId}::uuid, #{entity.channelType}, #{entity.name}, #{entity.remark}, " +
            "#{entity.accountIdentifier}, #{entity.accountIdentifierNormalized}, 'active', 'idle', " +
            "#{entity.onboardingMode}, #{entity.phoneVerificationStatus}, #{entity.providerPhoneStatus}, " +
            "#{entity.encryptedConfig}::jsonb, #{entity.providerScopeId}::uuid)")
    int insertOwned(@Param("entity") ChannelAccountEntity entity, @Param("ownerId") UUID ownerId);

    @Update("update channel_accounts set name = #{entity.name}, auth_status = 'active', " +
            "sync_status = 'idle', encrypted_config = #{entity.encryptedConfig}::jsonb, " +
            "updated_at = now(), version = version + 1 where id = #{entity.id}::uuid " +
            "and owner_user_id = #{ownerId}::uuid and auth_status = 'disabled' and deleted_at is null")
    int rebindOwned(@Param("entity") ChannelAccountEntity entity, @Param("ownerId") UUID ownerId);

    @Update("update channel_accounts set auth_status = 'disabled', sync_status = 'idle', " +
            "encrypted_config = '{}'::jsonb, " +
            "updated_at = now(), version = version + 1 where id = #{id}::uuid " +
            "and owner_user_id = #{ownerId}::uuid and deleted_at is null")
    int disableOwned(@Param("ownerId") UUID ownerId, @Param("id") UUID id);

    @Select("select id, owner_user_id, channel_type, name, remark, account_identifier, " +
            "account_identifier_normalized, auth_status, sync_status, onboarding_mode, phone_verification_status, " +
            "provider_phone_status, encrypted_config, provider_scope_id, " +
            "last_synced_at, created_at, updated_at, deleted_at, version " +
            "from channel_accounts where id = #{id}::uuid and owner_user_id = #{ownerId}::uuid " +
            "and deleted_at is null limit 1")
    ChannelAccountEntity findByIdAndOwner(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    @Select("select id, owner_user_id, channel_type, name, remark, account_identifier, " +
            "account_identifier_normalized, auth_status, sync_status, onboarding_mode, phone_verification_status, " +
            "provider_phone_status, encrypted_config, provider_scope_id, " +
            "last_synced_at, created_at, updated_at, deleted_at, version " +
            "from channel_accounts where owner_user_id = #{ownerId}::uuid " +
            "and channel_type = #{channelType} and deleted_at is null " +
            "and auth_status in ('active', 'expired', 'failed') " +
            "order by created_at asc limit 2")
    List<ChannelAccountEntity> findByOwnerAndChannelType(@Param("ownerId") UUID ownerId,
                                                         @Param("channelType") String channelType);

    @Update("update channel_accounts set provider_scope_id = #{scopeId}::uuid, updated_at = now() " +
            "where id = #{accountId}::uuid and deleted_at is null")
    int bindProviderScope(@Param("accountId") UUID accountId, @Param("scopeId") UUID scopeId);

    @Select("select id, owner_user_id, channel_type, name, remark, account_identifier, account_identifier_normalized, " +
            "auth_status, sync_status, onboarding_mode, phone_verification_status, provider_phone_status, " +
            "encrypted_config, provider_scope_id, last_synced_at, created_at, updated_at, deleted_at, version " +
            "from channel_accounts where provider_scope_id = #{scopeId}::uuid " +
            "and channel_type in ('chatapp', 'whatsapp') " +
            "and auth_status in ('active', 'expired', 'failed') and deleted_at is null " +
            "order by created_at asc")
    List<ChannelAccountEntity> findActiveByScope(@Param("scopeId") UUID scopeId);

    @Select("select id, owner_user_id, channel_type, name, remark, account_identifier, account_identifier_normalized, " +
            "auth_status, sync_status, onboarding_mode, phone_verification_status, provider_phone_status, " +
            "encrypted_config, provider_scope_id, last_synced_at, created_at, updated_at, deleted_at, version " +
            "from channel_accounts where channel_type in ('chatapp', 'whatsapp') " +
            "and account_identifier_normalized = #{identifier} " +
            "and auth_status in ('active', 'expired', 'failed') and deleted_at is null limit 1")
    ChannelAccountEntity findActiveByNormalizedIdentifier(@Param("identifier") String identifier);

    @Update("update channel_accounts as target set owner_user_id = #{targetOwnerId}::uuid, " +
            "updated_at = now(), version = target.version + 1 " +
            "where target.id = #{accountId}::uuid and target.channel_type in ('chatapp', 'whatsapp') " +
            "and target.auth_status in ('active', 'expired', 'failed') and target.deleted_at is null " +
            "and not exists (select 1 from channel_accounts existing " +
            "where existing.owner_user_id = #{targetOwnerId}::uuid " +
            "and existing.channel_type in ('chatapp', 'whatsapp') " +
            "and existing.auth_status in ('active', 'expired', 'failed') " +
            "and existing.deleted_at is null and existing.id <> target.id) " +
            "and exists (select 1 from user_roles actor_role join roles actor_role_def " +
            "on actor_role_def.id = actor_role.role_id where actor_role.user_id = #{actorId}::uuid " +
            "and actor_role_def.code = 'admin')")
    int reassignActiveWhatsAppAccount(@Param("accountId") UUID accountId,
                                      @Param("targetOwnerId") UUID targetOwnerId,
                                      @Param("actorId") UUID actorId);

    @Update("update channel_accounts set auth_status = 'disabled', sync_status = 'idle', " +
            "updated_at = now(), version = version + 1 where id = #{accountId}::uuid " +
            "and channel_type in ('chatapp', 'whatsapp') and deleted_at is null")
    int disableWhatsAppAccount(@Param("accountId") UUID accountId);

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

    @Select("select id, owner_user_id, channel_type, name, remark, account_identifier, account_identifier_normalized, " +
            "auth_status, sync_status, onboarding_mode, phone_verification_status, provider_phone_status, " +
            "encrypted_config, provider_scope_id, last_synced_at, created_at, updated_at, " +
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

    @Update("UPDATE channel_accounts SET name = #{name}, updated_at = now() " +
            "WHERE id = #{id}::uuid AND owner_user_id = #{ownerId}::uuid AND deleted_at IS NULL")
    int updateNameOwned(@Param("ownerId") UUID ownerId, @Param("id") UUID id, @Param("name") String name);

    @Update("UPDATE channel_accounts SET account_identifier = #{accountIdentifier}, account_identifier_normalized = #{accountIdentifier}, updated_at = now() WHERE id = #{id}::uuid")
    int updateAccountIdentifier(@Param("id") UUID id, @Param("accountIdentifier") String accountIdentifier);

    @Update("UPDATE channel_accounts SET account_identifier = #{accountIdentifier}, " +
            "account_identifier_normalized = #{accountIdentifier}, updated_at = now() " +
            "WHERE id = #{id}::uuid AND owner_user_id = #{ownerId}::uuid AND deleted_at IS NULL")
    int updateAccountIdentifierOwned(@Param("ownerId") UUID ownerId, @Param("id") UUID id,
                                      @Param("accountIdentifier") String accountIdentifier);

    @Update("UPDATE channel_accounts SET name = #{name}, account_identifier = #{accountIdentifier}, account_identifier_normalized = #{accountIdentifier}, updated_at = now() WHERE id = #{id}::uuid")
    int updateNameAndIdentifier(@Param("id") UUID id, @Param("name") String name, @Param("accountIdentifier") String accountIdentifier);

    @Update("UPDATE channel_accounts SET name = #{name}, account_identifier = #{accountIdentifier}, " +
            "account_identifier_normalized = #{accountIdentifier}, updated_at = now() " +
            "WHERE id = #{id}::uuid AND owner_user_id = #{ownerId}::uuid AND deleted_at IS NULL")
    int updateNameAndIdentifierOwned(@Param("ownerId") UUID ownerId, @Param("id") UUID id,
                                     @Param("name") String name, @Param("accountIdentifier") String accountIdentifier);

    @Update("UPDATE channel_accounts SET encrypted_config = #{config}::jsonb, updated_at = now() WHERE id = #{id}::uuid")
    int updateEncryptedConfig(@Param("id") UUID id, @Param("config") String config);

    @Update("UPDATE channel_accounts SET encrypted_config = #{config}::jsonb, updated_at = now() " +
            "WHERE id = #{id}::uuid AND owner_user_id = #{ownerId}::uuid AND deleted_at IS NULL")
    int updateEncryptedConfigOwned(@Param("ownerId") UUID ownerId, @Param("id") UUID id,
                                   @Param("config") String config);

    @Update("UPDATE channel_accounts SET sync_status = #{status}, last_synced_at = #{lastSyncedAt}, updated_at = now() WHERE id = #{id}::uuid")
    int updateSyncStatus(@Param("id") UUID id, @Param("status") String status, @Param("lastSyncedAt") java.time.Instant lastSyncedAt);

    @Update("UPDATE channel_accounts SET sync_status = #{status}, last_synced_at = #{lastSyncedAt}, " +
            "updated_at = now() WHERE id = #{id}::uuid AND owner_user_id = #{ownerId}::uuid " +
            "AND deleted_at IS NULL")
    int updateSyncStatusOwned(@Param("ownerId") UUID ownerId, @Param("id") UUID id,
                              @Param("status") String status, @Param("lastSyncedAt") java.time.Instant lastSyncedAt);

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
