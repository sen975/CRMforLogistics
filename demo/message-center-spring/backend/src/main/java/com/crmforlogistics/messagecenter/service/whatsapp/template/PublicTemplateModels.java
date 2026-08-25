package com.crmforlogistics.messagecenter.service.whatsapp.template;

import java.util.List;

public final class PublicTemplateModels {
    private PublicTemplateModels() {}

    public record Page(List<PublicTemplate> items, int total, int page, int size) {}

    public record PublicTemplate(String code, String name, String language, String category,
                                 List<String> industries, String usecase, String topic,
                                 Content content) {}

    public record Content(String templateName, String sceneTemplateName, String externalTemplateCode,
                          String languageCode, String category, List<MessagePage> pages,
                          List<Variable> variables) {}

    public record MessagePage(String name, String text, List<Button> buttons) {}

    public record Variable(String code, String name, String example, String format) {}

    public record Button(String name, String type, String url) {}

    public record Query(String name, String language, String category, List<String> industries,
                        List<String> usecases, int page, int size) {
        public Query {
            industries = industries == null ? List.of() : List.copyOf(industries);
            usecases = usecases == null ? List.of() : List.copyOf(usecases);
        }
    }
}
