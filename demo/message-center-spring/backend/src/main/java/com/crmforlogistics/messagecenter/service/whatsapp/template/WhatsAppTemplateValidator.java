package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ButtonType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateButton;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WhatsAppTemplateValidator {
    private static final int MAX_BODY_LENGTH = 1_024;
    private static final int MAX_HEADER_OR_FOOTER_LENGTH = 60;
    private static final int MAX_BUTTONS = 10;
    private static final Pattern VARIABLE_PATTERN = Pattern.compile(
            "\\$\\(\\s*([A-Za-z][A-Za-z0-9_]*)\\s*\\)");
    private static final Pattern UNSUPPORTED_VARIABLE_PATTERN = Pattern.compile(
            "\\{\\{\\s*[A-Za-z][A-Za-z0-9_]*\\s*}}"
                    + "|\\$\\{\\s*[A-Za-z][A-Za-z0-9_]*\\s*}");

    public TemplateCommand validate(TemplateCommand command) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (command == null) {
            throw WhatsAppTemplateException.validation(Map.of("command", "is required"));
        }

        validateCategory(command, errors);
        List<TemplateComponent> components = command.components();
        List<TemplateComponent> bodies = components.stream()
                .filter(component -> component.type() == ComponentType.BODY)
                .toList();
        if (bodies.size() != 1) {
            errors.put("components", "exactly one BODY component is required");
        } else {
            validateBody(bodies.get(0), errors);
        }

        validateHeaderAndFooter(components, errors);
        validateButtons(components, errors);
        validateExamples(command, components, errors);

        if (!errors.isEmpty()) {
            throw WhatsAppTemplateException.validation(errors);
        }
        return command;
    }

    private void validateCategory(TemplateCommand command, Map<String, String> errors) {
        if (!"UTILITY".equals(command.category()) && !"MARKETING".equals(command.category())) {
            errors.put("category", "must be UTILITY or MARKETING");
        }
    }

    private void validateBody(TemplateComponent body, Map<String, String> errors) {
        if (body.text() == null || body.text().isBlank()) {
            errors.put("body.text", "is required");
        } else if (body.text().length() > MAX_BODY_LENGTH) {
            errors.put("body.text", "must not exceed 1024 characters");
        } else {
            validateVariableSyntax(body.text(), "body.text", errors);
        }
    }

    private void validateHeaderAndFooter(List<TemplateComponent> components, Map<String, String> errors) {
        List<TemplateComponent> headers = components.stream()
                .filter(component -> component.type() == ComponentType.HEADER)
                .toList();
        if (headers.size() > 1) {
            errors.put("header", "must contain at most one HEADER component");
        }
        for (TemplateComponent header : headers) {
            if (header.headerFormat() == HeaderFormat.TEXT) {
                validateTextLength(header.text(), "header.text", errors);
            } else if (header.headerFormat() == HeaderFormat.IMAGE
                    || header.headerFormat() == HeaderFormat.VIDEO
                    || header.headerFormat() == HeaderFormat.DOCUMENT) {
                if (header.mediaAssetId() == null || header.mediaAssetId().isBlank()) {
                    errors.put("header.mediaAssetId", "is required for media headers");
                }
            } else {
                errors.put("header.format", "is required");
            }
        }

        List<TemplateComponent> footers = components.stream()
                .filter(component -> component.type() == ComponentType.FOOTER)
                .toList();
        if (footers.size() > 1) {
            errors.put("footer", "must contain at most one FOOTER component");
        }
        footers.forEach(footer -> validateTextLength(footer.text(), "footer.text", errors));
    }

    private void validateTextLength(String text, String field, Map<String, String> errors) {
        if (text == null || text.isBlank()) {
            errors.put(field, "is required");
        } else if (text.length() > MAX_HEADER_OR_FOOTER_LENGTH) {
            errors.put(field, "must not exceed 60 characters");
        } else {
            validateVariableSyntax(text, field, errors);
        }
    }

    private void validateVariableSyntax(String text, String field, Map<String, String> errors) {
        if (UNSUPPORTED_VARIABLE_PATTERN.matcher(text).find()) {
            errors.put(field, "variables must use $(name) syntax");
        }
    }

    private void validateButtons(List<TemplateComponent> components, Map<String, String> errors) {
        List<TemplateComponent> buttonGroups = components.stream()
                .filter(component -> component.type() == ComponentType.BUTTONS)
                .toList();
        if (buttonGroups.size() > 1) {
            errors.put("buttons", "must contain at most one BUTTONS component");
            return;
        }
        if (buttonGroups.isEmpty()) {
            return;
        }

        List<TemplateButton> buttons = buttonGroups.get(0).buttons();
        if (buttons.size() > MAX_BUTTONS) {
            errors.put("buttons", "must contain at most 10 buttons");
        }
        long quickReplies = buttons.stream().filter(button -> button.type() == ButtonType.QUICK_REPLY).count();
        long urls = buttons.stream().filter(button -> button.type() == ButtonType.URL).count();
        long phoneNumbers = buttons.stream().filter(button -> button.type() == ButtonType.PHONE_NUMBER).count();
        if (quickReplies > 0 && (urls > 0 || phoneNumbers > 0)) {
            errors.put("buttons", "QUICK_REPLY buttons cannot be combined with URL or PHONE_NUMBER buttons");
        }
        if (urls > 2) {
            errors.put("buttons.url", "must contain at most 2 URL buttons");
        }
        if (phoneNumbers > 1) {
            errors.put("buttons.phoneNumber", "must contain at most 1 PHONE_NUMBER button");
        }
    }

    private void validateExamples(
            TemplateCommand command,
            List<TemplateComponent> components,
            Map<String, String> errors) {
        Set<String> variables = new LinkedHashSet<>();
        for (TemplateComponent component : components) {
            if (component.type() == ComponentType.BODY
                    || component.type() == ComponentType.HEADER && component.headerFormat() == HeaderFormat.TEXT) {
                variables.addAll(variablesIn(component.text()));
            }
        }
        if (!variables.equals(command.examples().keySet())) {
            errors.put("examples", "must contain exactly the variables used by BODY and text HEADER");
        }
    }

    private Set<String> variablesIn(String text) {
        Set<String> variables = new LinkedHashSet<>();
        if (text == null) {
            return variables;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(text);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return variables;
    }
}
