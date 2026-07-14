package com.crmforlogistics.wecomsaas;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public class MessageStore {
    private final Path messageFile;
    private final Path attachmentFile;

    public MessageStore(Config config) {
        this.messageFile = config.dataDir().resolve("messages.jsonl");
        this.attachmentFile = config.dataDir().resolve("attachments.jsonl");
    }

    public List<MessageRecord> messagesForContact(String contactId) throws IOException {
        return JsonSupport.readJsonl(messageFile, MessageRecord.class).stream()
                .filter(message -> contactId.equals(message.contactId))
                .sorted(Comparator.comparing(message -> message.timestamp == null ? "" : message.timestamp))
                .toList();
    }

    public MessageRecord findMessage(String id) throws IOException {
        for (MessageRecord message : JsonSupport.readJsonl(messageFile, MessageRecord.class)) {
            if (message.id != null && message.id.equals(id)) {
                return message;
            }
        }
        return null;
    }

    public void appendMessage(MessageRecord message) throws IOException {
        JsonSupport.appendJsonl(messageFile, message);
    }

    public List<AttachmentRecord> attachments() throws IOException {
        return JsonSupport.readJsonl(attachmentFile, AttachmentRecord.class);
    }

    public void appendAttachment(AttachmentRecord attachment) throws IOException {
        JsonSupport.appendJsonl(attachmentFile, attachment);
    }

    public void ensureSeedMessages() throws IOException {
        if (!JsonSupport.readJsonl(messageFile, MessageRecord.class).isEmpty()) {
            return;
        }
        appendMessage(message("msg-app-001", "contact-ext-001", "wecom_app", "outbound",
                "应用消息：欢迎使用企业微信 SaaS 工作台", "success", "2026-07-14T09:00:00Z",
                "{\"msgtype\":\"text\",\"agentid\":\"1000002\"}"));
        appendMessage(message("msg-archive-001", "contact-ext-001", "archive", "inbound",
                "会话存档：报价已确认", "archived", "2026-07-14T09:10:00Z",
                "{\"msgid\":\"archive-seed-001\",\"encrypt_random_key\":\"seed-key\",\"text\":{\"content\":\"报价已确认\"}}"));
        appendMessage(message("msg-kf-001", "contact-kf-001", "wecom_kf", "inbound",
                "访客进线咨询报价", "received", "2026-07-14T09:20:00Z",
                "{\"msgid\":\"kf-seed-001\",\"open_kfid\":\"wkf_demo\"}"));
        appendMessage(message("msg-data-001", "contact-member-001", "data_api", "inbound",
                "数据 API 同步：成员 Alex 已更新", "synced", "2026-07-14T09:30:00Z",
                "{\"job\":\"member_sync\",\"userid\":\"alex\"}"));
    }

    private static MessageRecord message(String id, String contactId, String channel, String direction,
                                         String text, String status, String timestamp, String rawJson) {
        MessageRecord message = new MessageRecord();
        message.id = id;
        message.tenantId = "tenant-demo";
        message.contactId = contactId;
        message.channel = channel;
        message.direction = direction;
        message.text = text;
        message.status = status;
        message.timestamp = timestamp;
        message.rawJson = rawJson;
        return message;
    }
}
