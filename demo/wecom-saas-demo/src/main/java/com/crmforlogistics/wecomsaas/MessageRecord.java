package com.crmforlogistics.wecomsaas;

import java.time.Instant;
import java.util.UUID;

public class MessageRecord {
    public String id;
    public String tenantId;
    public String contactId;
    public String channel;
    public String direction;
    public String text;
    public String status;
    public String timestamp;
    public String rawJson;
    public AttachmentRecord attachment;

    public static MessageRecord outbound(String tenantId, String contactId, String channel, String text, String rawJson) {
        MessageRecord message = new MessageRecord();
        message.id = "msg-" + UUID.randomUUID();
        message.tenantId = tenantId;
        message.contactId = contactId;
        message.channel = channel;
        message.direction = "outbound";
        message.text = text;
        message.status = "local_success";
        message.timestamp = Instant.now().toString();
        message.rawJson = rawJson;
        return message;
    }
}
