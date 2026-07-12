package com.crmforlogistics.messagecenter;

public class ChannelCapability {
    public String channel;
    public boolean available;
    public String reason;

    public ChannelCapability(String channel, boolean available, String reason) {
        this.channel = channel;
        this.available = available;
        this.reason = reason;
    }
}