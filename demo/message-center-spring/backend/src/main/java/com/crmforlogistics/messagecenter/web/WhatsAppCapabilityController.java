package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.channel.ChatAppCapabilityGate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/whatsapp/capability")
public class WhatsAppCapabilityController {
    private final ChatAppCapabilityGate gate;

    public WhatsAppCapabilityController(ChatAppCapabilityGate gate) {
        this.gate = gate;
    }

    @GetMapping
    public ChatAppCapabilityGate.Status status() {
        return gate.status();
    }
}
