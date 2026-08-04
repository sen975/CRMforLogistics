package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.dto.response.TemplateResponse;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatAppTemplateService {

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}|\\$\\(\\s*([A-Za-z0-9_.-]+)\\s*\\)|\\(\\s*([A-Za-z][A-Za-z0-9_.-]*)\\s*\\)");

    private final TemplateMapper templateMapper;

    public ChatAppTemplateService(TemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
    }

    public List<TemplateResponse> listAll() {
        return templateMapper.selectList(null).stream()
                .map(entity -> new TemplateResponse(
                        entity.getProviderTemplateId(),
                        entity.getName(),
                        entity.getLanguageCode(),
                        entity.getBody(),
                        extractPlaceholders(entity.getBody())))
                .toList();
    }

    private List<String> extractPlaceholders(String body) {
        List<String> result = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return result;
        }
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            String key = firstNonBlank(matcher.group(1), matcher.group(2), matcher.group(3));
            if (!key.isBlank() && !result.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
