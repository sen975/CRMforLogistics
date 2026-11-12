package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.crmforlogistics.messagecenter.typehandler.JsonbStringTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.UUID;

@TableName(value = "whatsapp_template_migration_exceptions", autoResultMap = true)
public class WhatsAppTemplateMigrationExceptionEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private String migrationKey;
    private String resourceType;
    private UUID resourceId;
    private String reasonCode;
    @TableField(value = "details_jsonb", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String detailsJsonb;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getMigrationKey() { return migrationKey; }
    public void setMigrationKey(String migrationKey) { this.migrationKey = migrationKey; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public UUID getResourceId() { return resourceId; }
    public void setResourceId(UUID resourceId) { this.resourceId = resourceId; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getDetailsJsonb() { return detailsJsonb; }
    public void setDetailsJsonb(String detailsJsonb) { this.detailsJsonb = detailsJsonb; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
