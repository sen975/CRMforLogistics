package com.crmforlogistics.emaildemo;

public class InboxRecord {
    private final String id;
    private final String storedAt;
    private final String direction;
    private final String contactEmail;
    private final String contactName;
    private final String from;
    private final String to;
    private final String subject;
    private final String sentDate;
    private final String summary;
    private final String bodyText;
    private final String messageId;

    public InboxRecord(
            String id,
            String storedAt,
            String direction,
            String contactEmail,
            String contactName,
            String from,
            String to,
            String subject,
            String sentDate,
            String summary,
            String bodyText,
            String messageId
    ) {
        this.id = id;
        this.storedAt = storedAt;
        this.direction = direction;
        this.contactEmail = contactEmail;
        this.contactName = contactName;
        this.from = from;
        this.to = to;
        this.subject = subject;
        this.sentDate = sentDate;
        this.summary = summary;
        this.bodyText = bodyText;
        this.messageId = messageId;
    }

    public String id() { return id; }
    public String storedAt() { return storedAt; }
    public String direction() { return direction; }
    public String contactEmail() { return contactEmail; }
    public String contactName() { return contactName; }
    public String from() { return from; }
    public String to() { return to; }
    public String subject() { return subject; }
    public String sentDate() { return sentDate; }
    public String summary() { return summary; }
    public String bodyText() { return bodyText; }
    public String messageId() { return messageId; }
}
