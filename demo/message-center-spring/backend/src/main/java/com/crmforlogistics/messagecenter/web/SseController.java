package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.event.EventHub;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class SseController {
    private final EventHub eventHub;

    public SseController(EventHub eventHub) {
        this.eventHub = eventHub;
    }

    @GetMapping("/events")
    public SseEmitter streamEvents() {
        return eventHub.connect();
    }
}
