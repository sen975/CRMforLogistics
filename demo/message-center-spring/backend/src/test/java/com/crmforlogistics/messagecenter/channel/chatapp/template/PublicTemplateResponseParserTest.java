package com.crmforlogistics.messagecenter.channel.chatapp.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicTemplateResponseParserTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void projectsListBaseTemplateFixture() throws Exception {
        Map<String, Object> raw = readFixture("list-base-template.json");

        com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Page page =
                PublicTemplateResponseParser.parse(raw, 1, 200);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(template -> {
            assertThat(template.code()).isEqualTo("1174248293003616256");
            assertThat(template.name()).isEqualTo("account_creation_confirmation_3");
            assertThat(template.language()).isEqualTo("zh_CN");
            assertThat(template.content().variables()).singleElement().satisfies(variable ->
                    assertThat(variable.code()).isEqualTo("text"));
            assertThat(template.content().pages()).singleElement().satisfies(messagePage -> {
                assertThat(messagePage.name()).isEqualTo("page1");
                assertThat(messagePage.text()).isEqualTo("$(text)，您好：您的新帐号已成功创建。");
                assertThat(messagePage.buttons()).singleElement().satisfies(button -> {
                    assertThat(button.name()).isEqualTo("验证帐号");
                    assertThat(button.type()).isEqualTo("visitWebsite");
                    assertThat(button.url()).isEqualTo("https://www.example.com");
                });
            });
        });
    }

    @Test
    void acceptsAnEmptyListFixture() throws Exception {
        var page = PublicTemplateResponseParser.parse(readFixture("list-base-template-empty.json"), 1, 200);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
    }

    @Test
    void rejectsInvalidEnvelopeAsStructuredProviderErrorWithoutLeakingItsBody() throws Exception {
        assertThatThrownBy(() -> PublicTemplateResponseParser.parse(readFixture("list-base-template-invalid.json"), 1, 200))
                .isInstanceOf(WhatsAppTemplateException.class)
                .satisfies(error -> {
                    WhatsAppTemplateException exception = (WhatsAppTemplateException) error;
                    assertThat(exception.code()).isEqualTo("PUBLIC_TEMPLATE_RESPONSE_INVALID");
                    assertThat(exception.providerRequestId()).isEqualTo("invalid-request-id");
                    assertThat(exception.getMessage()).doesNotContain("provider fixture secret");
                });
    }

    @Test
    void rejectsInvalidRecordCountsRequiredFieldsAndArrayBounds() {
        assertInvalid(Map.of("Success", true));
        assertInvalid(envelopeWith(java.util.stream.IntStream.range(0, 201)
                .mapToObj(index -> template()).toList()));

        for (String field : List.of("Code", "Name", "Language")) {
            Map<String, Object> missing = template();
            missing.remove(field);
            assertInvalid(envelopeWith(List.of(missing)));
        }
        for (String field : List.of("Code", "Name", "Language", "Category", "Usecase", "Topic")) {
            Map<String, Object> oversized = template();
            oversized.put(field, "x".repeat(513));
            assertInvalid(envelopeWith(List.of(oversized)));
        }

        Map<String, Object> pages = template();
        pages.put("Content", contentWith("pageDTOList", repeated(33, Map.of("pageName", "page"))));
        assertInvalid(envelopeWith(List.of(pages)));

        Map<String, Object> variables = template();
        variables.put("Content", contentWith("variablesDTOList",
                repeated(65, Map.of("variableCode", "variable"))));
        assertInvalid(envelopeWith(List.of(variables)));

        Map<String, Object> buttons = template();
        buttons.put("Content", contentWith("pageDTOList", List.of(Map.of("pageLinkList",
                repeated(33, Map.of("linkName", "button", "linkType", "URL"))))));
        assertInvalid(envelopeWith(List.of(buttons)));

        Map<String, Object> buttonsAcrossPages = template();
        buttonsAcrossPages.put("Content", contentWith("pageDTOList", List.of(
                Map.of("pageLinkList", repeated(17, Map.of("linkName", "button", "linkType", "URL"))),
                Map.of("pageLinkList", repeated(17, Map.of("linkName", "button", "linkType", "URL"))))));
        assertInvalid(envelopeWith(List.of(buttonsAcrossPages)));

        Map<String, Object> industries = template();
        industries.put("Industry", repeated(33, "industry"));
        assertInvalid(envelopeWith(List.of(industries)));
    }

    @Test
    void rejectsObjectAndArrayValuesWhereTheProviderContractRequiresText() {
        Map<String, Object> objectCode = template();
        objectCode.put("Code", Map.of("provider", "value that must not be stringified"));
        assertInvalid(envelopeWith(List.of(objectCode)));

        Map<String, Object> arrayName = template();
        arrayName.put("Name", List.of("value that must not be stringified"));
        assertInvalid(envelopeWith(List.of(arrayName)));

        Map<String, Object> objectContentText = template();
        Map<String, Object> content = contentWith(null, null);
        content.put("templateName", Map.of("provider", "value that must not be stringified"));
        objectContentText.put("Content", content);
        assertInvalid(envelopeWith(List.of(objectContentText)));
    }

    @Test
    void rejectsPresentNestedCollectionsWithWrongTypes() {
        for (String field : List.of("Industry")) {
            Map<String, Object> row = template();
            row.put(field, "not-a-list");
            assertInvalid(envelopeWith(List.of(row)));
        }

        Map<String, Object> pages = template();
        pages.put("Content", contentWith("pageDTOList", "not-a-list"));
        assertInvalid(envelopeWith(List.of(pages)));

        Map<String, Object> variables = template();
        variables.put("Content", contentWith("variablesDTOList", Map.of()));
        assertInvalid(envelopeWith(List.of(variables)));

        Map<String, Object> links = template();
        links.put("Content", contentWith("pageDTOList", List.of(Map.of("pageLinkList", "not-a-list"))));
        assertInvalid(envelopeWith(List.of(links)));
    }

    @Test
    void rejectsMalformedNestedPageStructures() {
        Map<String, Object> title = template();
        title.put("Content", contentWith("pageDTOList", List.of(Map.of("rcsTitle", "not-an-object"))));
        assertInvalid(envelopeWith(List.of(title)));

        Map<String, Object> link = template();
        link.put("Content", contentWith("pageDTOList", List.of(Map.of("pageLinkList", List.of("not-an-object")))));
        assertInvalid(envelopeWith(List.of(link)));

        Map<String, Object> action = template();
        action.put("Content", contentWith("pageDTOList", List.of(Map.of("pageLinkList",
                List.of(Map.of("action", "not-an-object"))))));
        assertInvalid(envelopeWith(List.of(action)));
    }

    private void assertInvalid(Map<String, Object> envelope) {
        assertThatThrownBy(() -> PublicTemplateResponseParser.parse(envelope, 1, 200))
                .isInstanceOf(WhatsAppTemplateException.class)
                .satisfies(error -> assertThat(((WhatsAppTemplateException) error).code())
                        .isEqualTo("PUBLIC_TEMPLATE_RESPONSE_INVALID"));
    }

    private Map<String, Object> envelopeWith(List<Map<String, Object>> rows) {
        return new LinkedHashMap<>(Map.of("Success", true, "Code", "OK", "Data", rows));
    }

    private Map<String, Object> template() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("Code", "code");
        result.put("Name", "name");
        result.put("Language", "zh_CN");
        result.put("Category", "UTILITY");
        result.put("Industry", List.of());
        result.put("Content", contentWith(null, null));
        return result;
    }

    private Map<String, Object> contentWith(String key, Object value) {
        Map<String, Object> content = new LinkedHashMap<>();
        if (key != null) content.put(key, value);
        return content;
    }

    private List<Object> repeated(int count, Object value) {
        return new ArrayList<>(java.util.Collections.nCopies(count, value));
    }

    private Map<String, Object> readFixture(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/chatapp/" + name)) {
            return objectMapper.readValue(input, new TypeReference<>() {});
        }
    }
}
