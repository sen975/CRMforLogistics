package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.TemplateResponse;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class TemplateController {

    private final ChatAppTemplateService templateService;

    public TemplateController(ChatAppTemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping("/templates")
    public List<TemplateResponse> listTemplates() {
        return templateService.listAll();
    }
}
