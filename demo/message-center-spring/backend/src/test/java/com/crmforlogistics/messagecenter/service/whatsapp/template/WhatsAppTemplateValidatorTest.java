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

    /**
     * 变量落在首尾被平台直接拒审（拒审原话 {@code Variables can't be at the start or end of
     * the template}，拒审码 {@code Leading or Trailing Params Not Allowed}），
     * 所以在提交之前挡住，不让用户白等一轮、再换个模板名重走。
     *
     * <p>三种形态都要拦：变量打头、变量收尾、以及垫了空格的同名写法。
     * 第二种是最容易踩的 —— 中文里「您好 $(customer)」是最自然的问候写法。
     */
    @Test
    void rejectsVariablesAtTheStartOrEndOfTheBody() {
        Map<String, List<String>> examples = Map.of("customer", List.of("Alice"));

        assertValidationError(command(List.of(body("$(customer)，您好")), examples),
                "body.text", "must not start or end with a variable");
        assertValidationError(command(List.of(body("您好 $(customer)")), examples),
                "body.text", "must not start or end with a variable");
        assertValidationError(command(List.of(body("  $(customer) 您好")), examples),
                "body.text", "must not start or end with a variable");
    }

    /** 变量两侧都有固定文字是唯一被接受的形态 —— 顺带钉住它不会被上面那条误伤。 */
    @Test
    void acceptsVariablesThatAreNeitherAtTheStartNorAtTheEndOfTheBody() {
        TemplateCommand command = command(List.of(body("您好，$(customer)，您的货物已发出")),
                Map.of("customer", List.of("Alice")));

        assertThat(validator.validate(command)).isEqualTo(command);
    }

    /**
     * 页脚比正文更严：<b>一个变量都不能有</b>。
     *
     * <p>这与正文那条是两回事 —— 平台对 FOOTER 组件的口径是「不支持参数」，
     * 不是「参数位置受限」。所以这两条必须分别判，不能合并。
     */
    @Test
    void rejectsAnyVariableInTheFooter() {
        assertValidationError(command(List.of(
                        body("您好，$(customer)，您的货物已发出"),
                        footer("退订请回 $(customer)")),
                Map.of("customer", List.of("Alice"))),
                "footer.text", "must not contain variables");
    }

    /**
     * 「首尾不能是变量」只适用于正文，不适用于标题。
     *
     * <p>平台的 TEXT header 是「至多 1 个变量」，变量占满整个标题（{@code $(subject)}）
     * 是合法且常见的形态。这条用例钉住这两条规则的边界 ——
     * 顺手把正文那条推广到标题上，会拒掉平台本来收的模板。
     */
    @Test
    void doesNotApplyTheBodyPlacementRuleToTheHeader() {
        TemplateCommand command = command(List.of(
                        headerText("$(subject)"),
                        body("您好，$(customer)，您的货物已发出")),
                Map.of("customer", List.of("Alice"), "subject", List.of("发货通知")));

        assertThat(validator.validate(command)).isEqualTo(command);
    }

    /**
     * 「两个变量相邻」刻意<b>不</b>拦：平台口径不一（拒审原因表把它列为拒审，
     * 内容模板文档又写作 should not be adjacent，建议级），而本地校验只做确定的拦截。
     *
     * <p>这条用例钉住的是那个取舍本身：将来要改成拦，得先把口径定死再回来改这里。
     */
    @Test
    void leavesAdjacentVariablesToThePlatformBecauseThatRuleIsNotSettled() {
        TemplateCommand command = command(List.of(body("您好，$(first)$(last)，请查收")),
                Map.of("first", List.of("张"), "last", List.of("总")));

        assertThat(validator.validate(command)).isEqualTo(command);
    }

    @Test
    void acceptsTextHeaderAndFooterAt60Characters() {
        TemplateCommand command = command(List.of(
                headerText("h".repeat(60)),
                body("Hello $(customer), welcome"),
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
                headerText("Hi $(salesRep)"), body("Hello $(customer), welcome")),
                Map.of("customer", List.of("Alice"), "salesRep", List.of("Sam")));
        assertThat(validator.validate(valid)).isEqualTo(valid);

        assertValidationError(command(List.of(body("Hello $(customer), welcome")), Map.of()),
                "examples", "must contain exactly the variables used by BODY and text HEADER");
        assertValidationError(command(List.of(body("Hello $(customer), welcome")),
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

        TemplateCommand command = command(List.of(body("Hello $(customer), welcome")), examples);
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
