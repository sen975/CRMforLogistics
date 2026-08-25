package com.crmforlogistics.messagecenter.channel.chatapp.template;

import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.*;

final class PublicTemplateResponseParser {
    private static final int MAX_ITEMS = 200;
    private static final int MAX_TEXT = 512;
    private static final int MAX_PAGES = 32;
    private static final int MAX_VARIABLES = 64;
    private static final int MAX_BUTTONS = 32;
    private static final int MAX_INDUSTRIES = 32;

    private PublicTemplateResponseParser() {}

    static Page parse(Map<String, Object> envelope, int page, int size) {
        String requestId = requestId(envelope);
        try {
            if (envelope == null || !Boolean.TRUE.equals(envelope.get("Success"))
                    || !"OK".equals(String.valueOf(envelope.get("Code")))) {
                throw invalid(requestId);
            }
            Object rawData = envelope.get("Data");
            if (!(rawData instanceof List<?> data) || data.size() > MAX_ITEMS) throw invalid(requestId);
            List<PublicTemplate> items = new ArrayList<>();
            for (Object raw : data) {
                if (!(raw instanceof Map<?, ?> row)) throw invalid(requestId);
                items.add(parseTemplate(row, requestId));
            }
            int total = number(envelope.get("Total"), items.size(), requestId);
            return new Page(List.copyOf(items), total, page, size);
        } catch (WhatsAppTemplateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid(requestId);
        }
    }

    private static PublicTemplate parseTemplate(Map<?, ?> row, String requestId) {
        String code = required(row.get("Code"), requestId);
        String name = required(row.get("Name"), requestId);
        String language = required(row.get("Language"), requestId);
        String category = text(row.get("Category"), requestId);
        Content content = parseContent(row.get("Content"), requestId);
        return new PublicTemplate(code, name, language, category,
                strings(row.get("Industry"), requestId), text(row.get("Usecase"), requestId),
                text(row.get("Topic"), requestId), content);
    }

    private static Content parseContent(Object raw, String requestId) {
        if (!(raw instanceof Map<?, ?> content)) throw invalid(requestId);
        List<MessagePage> pages = new ArrayList<>();
        int[] totalButtons = {0};
        Object rawPages = content.get("pageDTOList");
        if (rawPages != null && !(rawPages instanceof List<?>)) throw invalid(requestId);
        if (rawPages instanceof List<?> list) {
            if (list.size() > MAX_PAGES) throw invalid(requestId);
            for (Object rawPage : list) {
                if (!(rawPage instanceof Map<?, ?> page)) throw invalid(requestId);
                pages.add(parsePage(page, requestId, totalButtons));
            }
        }
        List<Variable> variables = new ArrayList<>();
        Object rawVariables = content.get("variablesDTOList");
        if (rawVariables != null && !(rawVariables instanceof List<?>)) throw invalid(requestId);
        if (rawVariables instanceof List<?> list) {
            if (list.size() > MAX_VARIABLES) throw invalid(requestId);
            for (Object rawVariable : list) {
                if (!(rawVariable instanceof Map<?, ?> variable)) throw invalid(requestId);
                variables.add(new Variable(required(variable.get("variableCode"), requestId),
                        text(variable.get("variableName"), requestId), text(variable.get("defaultValue"), requestId),
                        text(variable.get("format"), requestId)));
            }
        }
        return new Content(text(content.get("templateName"), requestId), text(content.get("sceneTemplateName"), requestId),
                text(content.get("externalTemplateCode"), requestId), text(content.get("languageCode"), requestId),
                text(content.get("whatsappCatagory"), requestId), List.copyOf(pages),
                limited(variables, MAX_VARIABLES, requestId));
    }

    private static MessagePage parsePage(Map<?, ?> page, String requestId, int[] totalButtons) {
        String name = text(page.get("pageName"), requestId);
        Object rawTitle = page.get("rcsTitle");
        if (rawTitle != null && !(rawTitle instanceof Map<?, ?>)) throw invalid(requestId);
        String body = rawTitle instanceof Map<?, ?> title
                ? text(title.get("realContent"), requestId) : null;

        List<Button> buttons = new ArrayList<>();
        Object rawLinks = page.get("pageLinkList");
        if (rawLinks != null && !(rawLinks instanceof List<?>)) throw invalid(requestId);
        if (rawLinks instanceof List<?> links) {
            if (links.size() > MAX_BUTTONS) throw invalid(requestId);
            for (Object rawLink : links) {
                if (!(rawLink instanceof Map<?, ?> link)) throw invalid(requestId);
                Object rawAction = link.get("action");
                if (rawAction != null && !(rawAction instanceof Map<?, ?>)) throw invalid(requestId);
                String url = rawAction instanceof Map<?, ?> action
                        ? text(action.get("url"), requestId) : null;
                buttons.add(new Button(text(link.get("linkName"), requestId),
                        text(link.get("linkType"), requestId), url));
                if (++totalButtons[0] > MAX_BUTTONS) throw invalid(requestId);
            }
        }
        return new MessagePage(name, body, List.copyOf(buttons));
    }

    private static List<String> strings(Object value, String requestId) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> values)) throw invalid(requestId);
        if (values.size() > MAX_INDUSTRIES) throw invalid(requestId);
        List<String> result = new ArrayList<>(values.size());
        for (Object item : values) {
            String text = text(item, requestId);
            if (text == null) throw invalid(requestId);
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static <T> List<T> limited(List<T> values, int limit, String requestId) {
        if (values.size() > limit) throw invalid(requestId);
        return List.copyOf(values);
    }

    private static String required(Object value, String requestId) {
        String result = text(value, requestId);
        if (result == null || result.isBlank()) throw invalid(requestId);
        return result;
    }

    private static String text(Object value, String requestId) {
        if (value == null) return null;
        if (!(value instanceof String result)) throw invalid(requestId);
        if (result.length() > MAX_TEXT) throw invalid(requestId);
        return result;
    }

    private static int number(Object value, int fallback, String requestId) {
        if (value instanceof Number number) return Math.max(0, number.intValue());
        try { return value == null ? fallback : Math.max(0, Integer.parseInt(String.valueOf(value))); }
        catch (NumberFormatException ignored) { throw invalid(requestId); }
    }

    private static String requestId(Map<String, Object> envelope) {
        if (envelope == null || !(envelope.get("RequestId") instanceof String value) || value.length() > MAX_TEXT) {
            return null;
        }
        return value;
    }

    private static WhatsAppTemplateException invalid(String requestId) {
        return new WhatsAppTemplateException("PUBLIC_TEMPLATE_RESPONSE_INVALID", HttpStatus.BAD_GATEWAY,
                "Public template provider response is invalid", Map.of(), requestId, false);
    }
}
