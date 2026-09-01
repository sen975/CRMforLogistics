package com.crmforlogistics.messagecenter.channel.wecom;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("wecom_chatdata_messages")
public class WeComChatDataMessageEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID installationId;
    private UUID sourceConversationId;
    private UUID senderPartyId;
    private String conversationType;
    private String receiverPartyIds;
    private String msgid;
    private String secretKey;
    private String externalUserid;
    private String userid;
    private Long sendTime;
    private String msgtype;
    private String direction;
    private String ingestStatus;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getInstallationId() { return installationId; }
    public void setInstallationId(UUID installationId) { this.installationId = installationId; }

    public UUID getSourceConversationId() { return sourceConversationId; }
    public void setSourceConversationId(UUID sourceConversationId) { this.sourceConversationId = sourceConversationId; }

    public UUID getSenderPartyId() { return senderPartyId; }
    public void setSenderPartyId(UUID senderPartyId) { this.senderPartyId = senderPartyId; }

    public String getConversationType() { return conversationType; }
    public void setConversationType(String conversationType) { this.conversationType = conversationType; }

    public String getReceiverPartyIds() { return receiverPartyIds; }
    public void setReceiverPartyIds(String receiverPartyIds) { this.receiverPartyIds = receiverPartyIds; }

    public String getMsgid() { return msgid; }
    public void setMsgid(String msgid) { this.msgid = msgid; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getExternalUserid() { return externalUserid; }
    public void setExternalUserid(String externalUserid) { this.externalUserid = externalUserid; }

    public String getUserid() { return userid; }
    public void setUserid(String userid) { this.userid = userid; }

    public Long getSendTime() { return sendTime; }
    public void setSendTime(Long sendTime) { this.sendTime = sendTime; }

    public String getMsgtype() { return msgtype; }
    public void setMsgtype(String msgtype) { this.msgtype = msgtype; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getIngestStatus() { return ingestStatus; }
    public void setIngestStatus(String ingestStatus) { this.ingestStatus = ingestStatus; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
