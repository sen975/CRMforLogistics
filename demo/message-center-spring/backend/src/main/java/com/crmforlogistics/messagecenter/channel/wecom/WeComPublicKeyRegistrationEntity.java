package com.crmforlogistics.messagecenter.channel.wecom;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("wecom_public_key_registration")
public class WeComPublicKeyRegistrationEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private String authCorpId;
    private Integer publicKeyVersion;
    private String publicKeySha256;
    private Instant registeredAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getAuthCorpId() { return authCorpId; }
    public void setAuthCorpId(String authCorpId) { this.authCorpId = authCorpId; }

    public Integer getPublicKeyVersion() { return publicKeyVersion; }
    public void setPublicKeyVersion(Integer publicKeyVersion) { this.publicKeyVersion = publicKeyVersion; }

    public String getPublicKeySha256() { return publicKeySha256; }
    public void setPublicKeySha256(String publicKeySha256) { this.publicKeySha256 = publicKeySha256; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
}
