package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ButtonType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateButton;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhatsAppTemplateValidatorTest {

    private final WhatsAppTemplateValidator validator = new WhatsAppTemplateValidator();

    @Test
    void validatorIsDiscoverableBySpringComponentScan() {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);

        assertThat(scanner.findCandidateComponents(
                        WhatsAppTemplateValidator.class.getPackageName()))
                .extracting(org.springframework.beans.factory.config.BeanDefinition::getBeanClassName)
                .contains(WhatsAppTemplateValidator.class.getName());
    }

    @Test
    void acceptsExactlyOneBodyAtTheMaximumLength() {
        TemplateCommand command = command(
                List.of(body("a".repeat(1_024))),
                Map.of());

        assertThat(validator.validate(command)).isEqualTo(command);
    }

    @Test
    void rejectsMissingOrMultipleBodiesWithStableValidationErrors() {
        assertValidationError(command(List.of(), Map.of()), "components", "exactly one BODY component is required");
        assertValidationError(command(List.of(body("one"), body("two")), Map.of()),
                "components", "exactly one BODY component is required");
    }

    @Test
    void rejectsBodyLongerThan1024Characters() {
        assertValidationError(command(List.of(body("a".repeat(1_025))), Map.of()),
                "body.text", "must not exceed 1024 characters");
    }

    @Test
    void acceptsTextHeaderAndFooterAt60Characters() {
        TemplateCommand command = command(List.of(
                headerText("h".repeat(60)),
                body("Hello $(customer)"),
                footer("f".repeat(60))), Map.of("customer", List.of("Alice")));

        assertThat(validator.validate(command)).isEqualTo(command);
    }

    @Test
    void rejectsTextHeaderOrFooterLongerThan60Characters() {
        assertValidationError(command(List.of(headerText("h".repeat(61)), body("hello")), Map.of()),
                "header.text", "must not exceed 60 characters");
        assertValidationError(command(List.of(body("hello"), footer("f".repeat(61))), Map.of()),
                "footer.text", "must not exceed 60 characters");
    }

    @Test
    void acceptsOnlySupportedCategories() {
        assertThat(validator.validate(command(List.of(body("hello")), Map.of()))).isNotNull();
        TemplateCommand marketing = new TemplateCommand(
                "promotion", "en_US", "MARKETING", List.of(body("hello")), Map.of(), null, "request-2");
        assertThat(validator.validate(marketing)).isNotNull();

        TemplateCommand unsupported = new TemplateCommand(
                "order_update", "en_US", "AUTHENTICATION", List.of(body("hello")), Map.of(), null, "request-1");
        assertValidationError(unsupported, "category", "must be UTILITY or MARKETING");
    }

    @Test
    void requiresAnUploadedMediaAssetReferenceForMediaHeaders() {
        for (HeaderFormat format : List.of(HeaderFormat.IMAGE, HeaderFormat.VIDEO, HeaderFormat.DOCUMENT)) {
            assertValidationError(command(List.of(
                    new TemplateComponent(ComponentType.HEADER, format, null, null, List.of()),
                    body("hello")), Map.of()), "header.mediaAssetId", "is required for media headers");

            assertThat(validator.validate(command(List.of(
                    new TemplateComponent(ComponentType.HEADER, format, null, "asset-1", List.of()),
                    body("hello")), Map.of())))
                    .as("media header format %s", format)
                    .isNotNull();
        }
    }

    @Test
    void rejectsMoreThanOneButtonGroupMoreThanTenButtonsAndExcessTypedButtons() {
        TemplateComponent elevenButtons = buttons(List.of(
                button(ButtonType.QUICK_REPLY), button(ButtonType.QUICK_REPLY), button(ButtonType.QUICK_REPLY),
                button(ButtonType.QUICK_REPLY), button(ButtonType.QUICK_REPLY), button(ButtonType.QUICK_REPLY),
                button(ButtonType.QUICK_REPLY), button(ButtonType.QUICK_REPLY), button(ButtonType.QUICK_REPLY),
                button(ButtonType.QUICK_REPLY), button(ButtonType.QUICK_REPLY)));
        assertValidationError(command(List.of(body("hello"), elevenButtons), Map.of()),
                "buttons", "must contain at most 10 buttons");

        assertValidationError(command(List.of(body("hello"), buttons(List.of()), buttons(List.of())), Map.of()),
                "buttons", "must contain at most one BUTTONS component");

        assertValidationError(command(List.of(body("hello"), buttons(List.of(
                button(ButtonType.URL), button(ButtonType.URL), button(ButtonType.URL)))), Map.of()),
                "buttons.url", "must contain at most 2 URL buttons");
        assertValidationError(command(List.of(body("hello"), buttons(List.of(
                button(ButtonType.PHONE_NUMBER), button(ButtonType.PHONE_NUMBER)))), Map.of()),
                "buttons.phoneNumber", "must contain at most 1 PHONE_NUMBER button");
    }

    @Test
    void rejectsMixingQuickReplyButtonsWithUrlOrPhoneButtons() {
        assertValidationError(command(List.of(body("hello"), buttons(List.of(
                button(ButtonType.QUICK_REPLY), button(ButtonType.URL)))), Map.of()),
                "buttons", "QUICK_REPLY buttons cannot be combined with URL or PHONE_NUMBER buttons");
        assertValidationError(command(List.of(body("hello"), buttons(List.of(
                button(ButtonType.QUICK_REPLY), button(ButtonType.PHONE_NUMBER)))), Map.of()),
                "buttons", "QUICK_REPLY buttons cannot be combined with URL or PHONE_NUMBER buttons");
    }

    @Test
    void requiresExamplesWithExactlyTheVariablesUsedByBodyAndTextHeader() {
        TemplateCommand valid = command(List.of(
                headerText("Hi $(salesRep)"), body("Hello $(customer)")),
                Map.of("customer", List.of("Alice"), "salesRep", List.of("Sam")));
        assertThat(validator.validate(valid)).isEqualTo(valid);

        assertValidationError(command(List.of(body("Hello $(customer)")), Map.of()),
                "examples", "must contain exactly the variables used by BODY and text HEADER");
        assertValidationError(command(List.of(body("Hello $(customer)")),
                Map.of("customer", List.of("Alice"), "extra", List.of("value"))),
                "examples", "must contain exactly the variables used by BODY and text HEADER");
    }

    @Test
    void rejectsUnsupportedVariableSyntaxInNewCommandsEvenWithoutExamples() {
        assertValidationError(command(List.of(body("Hello {{customer}}")), Map.of()),
                "body.text", "variables must use $(name) syntax");
        assertValidationError(command(List.of(body("Hello ${customer}")), Map.of()),
                "body.text", "variables must use $(name) syntax");
    }

    @Test
    void commandOwnsAnImmutableDeepCopyOfExamples() {
        List<String> customerExamples = new ArrayList<>(List.of("Alice"));
        Map<String, List<String>> examples = new LinkedHashMap<>();
        examples.put("customer", customerExamples);

        TemplateCommand command = command(List.of(body("Hello $(customer)")), examples);
        customerExamples.add("Bob");

        assertThat(command.examples().get("customer")).containsExactly("Alice");
        assertThatThrownBy(() -> command.examples().get("customer").add("Carol"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static TemplateCommand command(List<TemplateComponent> components, Map<String, List<String>> examples) {
        return new TemplateCommand("order_update", "en_US", "UTILITY", components, examples, null, "request-1");
    }

    private static TemplateComponent body(String text) {
        return new TemplateComponent(ComponentType.BODY, null, text, null, List.of());
    }

    private static TemplateComponent headerText(String text) {
        return new TemplateComponent(ComponentType.HEADER, HeaderFormat.TEXT, text, null, List.of());
    }

    private static TemplateComponent footer(String text) {
        return new TemplateComponent(ComponentType.FOOTER, null, text, null, List.of());
    }

    private static TemplateComponent buttons(List<TemplateButton> buttons) {
        return new TemplateComponent(ComponentType.BUTTONS, null, null, null, buttons);
    }

    private static TemplateButton button(ButtonType type) {
        return new TemplateButton(type, "Button", type == ButtonType.URL ? "https://example.com" : null,
                type == ButtonType.PHONE_NUMBER ? "+15551234567" : null);
    }

    private void assertValidationError(TemplateCommand command, String field, String message) {
        assertThatThrownBy(() -> validator.validate(command))
                .isInstanceOf(WhatsAppTemplateException.class)
                .satisfies(error -> {
                    WhatsAppTemplateException templateException = (WhatsAppTemplateException) error;
                    assertThat(templateException.code()).isEqualTo("TEMPLATE_VALIDATION_FAILED");
                    assertThat(templateException.getStatusCode().value()).isEqualTo(400);
                    assertThat(templateException.fieldErrors()).containsEntry(field, message);
                });
    }
}
