package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.mapper.ChatAppCapabilityResultMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ChatAppCapabilityGate {
    private final ChatAppCapabilityResultMapper results;

    public ChatAppCapabilityGate(ChatAppCapabilityResultMapper results) {
        this.results = results;
    }

    public Status status() {
        return new Status(results.latestReadOnlyReady());
    }

    public void requireReady() {
        if (!results.latestReadOnlyReady()) {
            throw new ChannelAccountException("WHATSAPP_CAPABILITY_NOT_VERIFIED", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public record Status(boolean ready) { }
}
