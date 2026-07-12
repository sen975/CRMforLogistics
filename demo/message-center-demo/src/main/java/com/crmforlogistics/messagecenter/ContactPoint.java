package com.crmforlogistics.messagecenter;

public class ContactPoint {
    public String id;
    public String channel;
    public String type;
    public String value;
    public String label;

    public ContactPoint() {}

    public ContactPoint(String id, String channel, String type, String value, String label) {
        this.id = id;
        this.channel = channel;
        this.type = type;
        this.value = value;
        this.label = label;
    }
}