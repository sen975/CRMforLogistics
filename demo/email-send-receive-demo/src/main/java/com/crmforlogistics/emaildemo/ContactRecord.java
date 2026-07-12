package com.crmforlogistics.emaildemo;

public class ContactRecord {
    private final String email;
    private final String name;
    private final String lastSubject;
    private final String lastTime;
    private final int messageCount;

    public ContactRecord(String email, String name, String lastSubject, String lastTime, int messageCount) {
        this.email = email;
        this.name = name;
        this.lastSubject = lastSubject;
        this.lastTime = lastTime;
        this.messageCount = messageCount;
    }

    public String email() { return email; }
    public String name() { return name; }
    public String lastSubject() { return lastSubject; }
    public String lastTime() { return lastTime; }
    public int messageCount() { return messageCount; }
}
