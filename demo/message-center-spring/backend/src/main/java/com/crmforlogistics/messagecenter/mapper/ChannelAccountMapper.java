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

    @Select("select count(*) from channel_accounts where owner_user_id = #{ownerId}::uuid " +
            "and channel_type in ('chatapp', 'whatsapp') " +
            "and auth_status in ('active', 'expired', 'failed') and deleted_at is null")
    int countActiveWhatsAppByOwner(@Param("ownerId") UUID ownerId);

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

    @Select("select id, owner_user_id, channel_type, name, remark, account_identifier, " +
            "account_identifier_normalized, auth_status, sync_status, onboarding_mode, phone_verification_status, " +
            "provider_phone_status, encrypted_config, provider_scope_id, last_synced_at, created_at, updated_at, " +
            "deleted_at, version from channel_accounts where id = #{accountId}::uuid " +
            "and channel_type in ('chatapp', 'whatsapp') and deleted_at is null for update")
    ChannelAccountEntity findWhatsAppByIdForUpdate(@Param("accountId") UUID accountId);

    @Update("update channel_accounts as target set owner_user_id = #{targetOwnerId}::uuid, " +
            "updated_at = now(), version = target.version + 1 " +
            "where target.id = #{accountId}::uuid and target.version = #{expectedVersion} " +
            "and target.owner_user_id is null " +
            "and target.channel_type in ('chatapp', 'whatsapp') " +
            "and target.auth_status in ('active', 'expired', 'failed') and target.deleted_at is null " +
            "and not exists (select 1 from channel_accounts existing " +
            "where existing.owner_user_id = #{targetOwnerId}::uuid " +
            "and existing.channel_type in ('chatapp', 'whatsapp') " +
            "and existing.auth_status in ('active', 'expired', 'failed') " +
            "and existing.deleted_at is null and existing.id <> target.id)")
    int assignWhatsAppOwner(@Param("accountId") UUID accountId,
                            @Param("targetOwnerId") UUID targetOwnerId,
                            @Param("expectedVersion") long expectedVersion);

    @Update("update channel_accounts set owner_user_id = null, updated_at = now(), version = version + 1 " +
            "where id = #{accountId}::uuid and version = #{expectedVersion} " +
            "and owner_user_id is not null and channel_type in ('chatapp', 'whatsapp') " +
            "and auth_status in ('active', 'expired', 'failed') and deleted_at is null")
    int reclaimWhatsAppOwner(@Param("accountId") UUID accountId,
                             @Param("expectedVersion") long expectedVersion);

    @Update("update channel_accounts as target set owner_user_id = #{targetOwnerId}::uuid, " +
            "updated_at = now(), version = target.version + 1 " +
            "where target.id = #{accountId}::uuid and target.version = #{expectedVersion} " +
            "and target.owner_user_id is not null and target.owner_user_id <> #{targetOwnerId}::uuid " +
            "and target.channel_type in ('chatapp', 'whatsapp') " +
            "and target.auth_status in ('active', 'expired', 'failed') and target.deleted_at is null " +
            "and not exists (select 1 from channel_accounts existing " +
            "where existing.owner_user_id = #{targetOwnerId}::uuid " +
            "and existing.channel_type in ('chatapp', 'whatsapp') " +
            "and existing.auth_status in ('active', 'expired', 'failed') " +
            "and existing.deleted_at is null and existing.id <> target.id)")
    int transferWhatsAppOwner(@Param("accountId") UUID accountId,
                              @Param("targetOwnerId") UUID targetOwnerId,
                              @Param("expectedVersion") long expectedVersion);

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

    @Select("select id, owner_user_id, channel_type, name, remark, account_identifier, " +
            "account_identifier_normalized, auth_status, sync_status, onboarding_mode, phone_verification_status, " +
            "provider_phone_status, encrypted_config, provider_scope_id, last_synced_at, created_at, updated_at, " +
            "deleted_at, version from channel_accounts " +
            "where channel_type in ('chatapp', 'whatsapp') and deleted_at is null " +
            "order by created_at asc")
    List<ChannelAccountEntity> findAllWhatsAppForSync();

    @Select("select id, owner_user_id, channel_type, name, remark, account_identifier, " +
            "account_identifier_normalized, auth_status, sync_status, onboarding_mode, phone_verification_status, " +
            "provider_phone_status, encrypted_config, provider_scope_id, last_synced_at, created_at, updated_at, " +
            "deleted_at, version from channel_accounts " +
            "where provider_scope_id = #{scopeId}::uuid and channel_type in ('chatapp', 'whatsapp') " +
            "and deleted_at is null order by created_at asc")
    List<ChannelAccountEntity> findAllWhatsAppByScope(@Param("scopeId") UUID scopeId);

    @Insert("insert into channel_accounts " +
            "(id, owner_user_id, channel_type, name, account_identifier, account_identifier_normalized, " +
            "auth_status, sync_status, onboarding_mode, phone_verification_status, provider_phone_status, " +
            "encrypted_config, provider_scope_id, last_synced_at) " +
            "values (#{entity.id}::uuid, null, 'chatapp', #{entity.name}, #{entity.accountIdentifier}, " +
            "#{entity.accountIdentifierNormalized}, 'active', 'success', 'ADMIN_API_WABA', " +
            "#{entity.phoneVerificationStatus}, #{entity.providerPhoneStatus}, #{entity.encryptedConfig}::jsonb, " +
            "#{scopeId}::uuid, #{lastSyncedAt}) " +
            "on conflict (channel_type, account_identifier_normalized) where deleted_at is null do update set " +
            "name = excluded.name, auth_status = excluded.auth_status, sync_status = excluded.sync_status, " +
            "phone_verification_status = excluded.phone_verification_status, " +
            "provider_phone_status = excluded.provider_phone_status, " +
            "encrypted_config = excluded.encrypted_config, provider_scope_id = excluded.provider_scope_id, " +
            "last_synced_at = excluded.last_synced_at, updated_at = now(), version = channel_accounts.version + 1")
    int upsertAdminSynced(@Param("entity") ChannelAccountEntity entity,
                          @Param("scopeId") UUID scopeId,
                          @Param("lastSyncedAt") java.time.Instant lastSyncedAt);

    // Only reachable for phones the provider reported usable, so it also clears a stale local
    // 'disabled' (written by unbind) — the admin re-imports the number by syncing.
    @Update("update channel_accounts set name = #{name}, account_identifier = #{accountIdentifier}, " +
            "account_identifier_normalized = #{accountIdentifierNormalized}, phone_verification_status = #{verificationStatus}, " +
            "provider_phone_status = #{providerStatus}, encrypted_config = #{encryptedConfig}::jsonb, " +
            "provider_scope_id = #{scopeId}::uuid, auth_status = 'active', sync_status = 'success', " +
            "last_synced_at = #{lastSyncedAt}, " +
            "updated_at = now(), version = version + 1 where id = #{accountId}::uuid and deleted_at is null")
    int refreshAdminSynced(@Param("accountId") UUID accountId,
                           @Param("scopeId") UUID scopeId,
                           @Param("name") String name,
                           @Param("accountIdentifier") String accountIdentifier,
                           @Param("accountIdentifierNormalized") String accountIdentifierNormalized,
                           @Param("verificationStatus") String verificationStatus,
                           @Param("providerStatus") String providerStatus,
                           @Param("encryptedConfig") String encryptedConfig,
                           @Param("lastSyncedAt") java.time.Instant lastSyncedAt);

    @Update("update channel_accounts set provider_phone_status = 'UNKNOWN', sync_status = 'failed', " +
            "last_synced_at = #{lastSyncedAt}, updated_at = now(), version = version + 1 " +
            "where provider_scope_id = #{scopeId}::uuid and channel_type in ('chatapp', 'whatsapp') " +
            "and deleted_at is null")
    int markWhatsAppProviderUnavailable(@Param("scopeId") UUID scopeId,
                                        @Param("lastSyncedAt") java.time.Instant lastSyncedAt);

    // Refines the blanket UNKNOWN written by the statement above: the provider did answer for this
    // number, so the row keeps the closest status the columns accept. Neither column can hold
    // everything the provider says — values outside their vocabulary arrive here as UNKNOWN / null,
    // and the raw ones stay in the sync response, which is what the page shows.
    @Update("update channel_accounts set provider_phone_status = #{providerStatus}, " +
            "phone_verification_status = #{verificationStatus}, " +
            "last_synced_at = #{lastSyncedAt}, updated_at = now(), version = version + 1 " +
            "where provider_scope_id = #{scopeId}::uuid and account_identifier_normalized = #{phone} " +
            "and channel_type in ('chatapp', 'whatsapp') and deleted_at is null")
    int recordProviderPhoneStatus(@Param("scopeId") UUID scopeId,
                                  @Param("phone") String phone,
                                  @Param("providerStatus") String providerStatus,
                                  @Param("verificationStatus") String verificationStatus,
                                  @Param("lastSyncedAt") java.time.Instant lastSyncedAt);

    // Only the provider status decides sendability; see AdminWhatsAppAccountSyncService#isUsable.
    @Select("select provider_scope_id as scope_id, count(*) as total from channel_accounts " +
            "where provider_scope_id is not null and deleted_at is null " +
            "and channel_type in ('chatapp', 'whatsapp') " +
            "and provider_phone_status = 'ACTIVE' " +
            "group by provider_scope_id")
    List<UsableAccountCountRow> countUsableAccountsByScope();

    record UsableAccountCountRow(UUID scopeId, long total) { }
}
