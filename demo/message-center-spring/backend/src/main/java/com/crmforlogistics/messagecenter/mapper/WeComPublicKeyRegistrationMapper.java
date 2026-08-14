package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.channel.wecom.WeComPublicKeyRegistrationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WeComPublicKeyRegistrationMapper extends BaseMapper<WeComPublicKeyRegistrationEntity> {

    @Select("SELECT * FROM wecom_public_key_registration WHERE auth_corp_id = #{authCorpId} "
            + "AND public_key_version = #{publicKeyVersion} AND public_key_sha256 = #{publicKeySha256}")
    WeComPublicKeyRegistrationEntity findRegistered(String authCorpId, int publicKeyVersion,
                                                    String publicKeySha256);

    @Insert("INSERT INTO wecom_public_key_registration (auth_corp_id, public_key_version, public_key_sha256, registered_at) "
            + "VALUES (#{authCorpId}, #{publicKeyVersion}, #{publicKeySha256}, now()) "
            + "ON CONFLICT (auth_corp_id, public_key_version) DO UPDATE "
            + "SET public_key_sha256 = EXCLUDED.public_key_sha256, registered_at = now()")
    int upsert(WeComPublicKeyRegistrationEntity entity);
}
