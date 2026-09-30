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
    /**
     * 正文 / 头尾 / 按钮的数量与长度上限。
     *
     * <p>2026-09-28 由 {@code private} 改为 {@code public}：助手工具 {@code chatapp.template_apply}
     * 要在<b>入参 schema</b> 里声明同一组上限（{@code maxLength} / {@code enum}），而 schema 是
     * 模型看到的第一道约束。抄第二份数字必然漂移 —— 校验改宽了而 schema 没改，模型会被一份
     * 过期的上限误导；schema 改宽了而校验没改，错误要拖到服务层才炸，且那时用户看到的是一句
     * 没法自己修的报错。<b>一处定义，两处引用。</b>
     */
    public static final int MAX_BODY_LENGTH = 1_024;
    public static final int MAX_HEADER_OR_FOOTER_LENGTH = 60;
    public static final int MAX_BUTTONS = 10;

    /**
     * 一个模板最多允许多少个 <b>URL 类型</b>的按钮。
     *
     * <p>它与 {@link #MAX_BUTTONS} 是两条独立的规则：{@code MAX_BUTTONS} 管按钮<b>总数</b>，
     * 这一条只管其中 URL 那一类的个数。助手工具 {@code chatapp.template_apply}
     * <b>开的槽位全是 URL 按钮</b>，所以对它真正生效的是这一条，不是总数那条。
     *
     * <p>2026-09-29 由字面量提为常量。那次的教训是：工具侧按 {@code MAX_BUTTONS}（总数）
     * 判过「开得不算多」，而这里单独卡 2 —— 结果模型填满 3 个链接按钮、被自己的服务层拒。
     * <b>判据：先认清是哪一条规则在管，再谈同源。</b>
     */
    public static final int MAX_URL_BUTTONS = 2;
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
            return;
        }
        if (body.text().length() > MAX_BODY_LENGTH) {
            errors.put("body.text", "must not exceed 1024 characters");
            return;
        }
        if (validateVariableSyntax(body.text(), "body.text", errors)) {
            // 写法都不对时先说写法：那段文本里「变量落在哪里」还无从谈起
            return;
        }
        if (startsOrEndsWithVariable(body.text())) {
            errors.put("body.text", "must not start or end with a variable");
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
        footers.forEach(footer -> validateFooter(footer.text(), errors));
    }

    /**
     * 页脚：长度与写法之外，还多一条正文没有的约束 —— <b>整体不许出现变量</b>。
     *
     * <h2>为什么页脚是「一个都不能有」，而正文只是「不能在首尾」</h2>
     * 平台对 FOOTER 组件的口径是它不支持参数（页脚只能是静态文本），
     * 不是「变量位置受限」。所以正文能放变量、只要不在首尾，页脚则一个都不行 ——
     * 两条规则的判据不同，不能合并成一条。
     *
     * <p>这条事实此前只活在渲染层的注释里（前端 {@code TemplateMessagePreview} 早就记着
     * 「平台的 FOOTER 组件不允许变量」），既没进校验器也没进给模型的描述，
     * 于是模型会把「退订请回 $(name)」这种页脚照写不误。
     *
     * <p>只在长度/写法都对时才追加这一条：一个字段在一个响应里只报一条错，
     * 否则用户改完变量还要再撞一次长度。
     */
    private void validateFooter(String text, Map<String, String> errors) {
        validateTextLength(text, "footer.text", errors);
        if (!errors.containsKey("footer.text") && hasVariable(text)) {
            errors.put("footer.text", "must not contain variables");
        }
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

    /**
     * 变量写法是否合规；不合规时已写入 {@code errors} 并返回 {@code true}。
     *
     * <p>返回值是给 {@link #validateBody} 用的：它在这之后还要判「变量落在哪」，
     * 而写法都不对的文本里根本认不出变量（{@code {{name}}} 在本项目的语法下不是一个变量），
     * 这时报位置错误只会让用户改错地方。
     */
    private boolean validateVariableSyntax(String text, String field, Map<String, String> errors) {
        if (UNSUPPORTED_VARIABLE_PATTERN.matcher(text).find()) {
            errors.put(field, "variables must use $(name) syntax");
            return true;
        }
        return false;
    }

    /**
     * 变量是否落在字符串的开头或结尾。
     *
     * <h2>为什么它必须挡住，而不是留给平台拒审</h2>
     * 平台的硬规则（拒审原话 {@code Variables can't be at the start or end of the template}，
     * 拒审码 {@code Leading or Trailing Params Not Allowed}）：正文以变量开头或结尾会被直接拒。
     * 触发之后模板进不了可发送态，用户白等一轮审核，还要换个模板名重走 ——
     * 而这一条<b>在提交之前就能确定地判出来</b>，没有理由让它拖到平台那一步。
     *
     * <p>它对中文场景尤其容易踩：「您好 $(customer_name)」是中文里最自然的问候写法，
     * 而它恰好是变量收尾。工具描述与前端表单都按同一口径拦这一形态。
     *
     * <p><b>先 strip 再判</b>（而不是拿原串比）：{@code " $(name) 您好"} 这种「前面垫一个空格」
     * 的写法，意图仍然是让变量打头，从严判成违规；反过来说，strip 只去掉首尾空白，
     * 不会把任何"固定文字开头"的合法正文误判成违规。
     *
     * <p><b>刻意不拦「两个变量相邻」</b>：同一族规则的官方口径不一致 ——
     * Twilio 的拒审原因表把相邻列为拒审（{@code {{1}}{{2}}}），
     * 而它的内容模板文档又写作 "shouldn't be adjacent"（建议级）。
     * 本地校验的意义是<b>确定的</b>拦截，口径有争议的规则拦下来就是误伤，
     * 所以相邻只出现在给模型的描述里当建议，不在这里判。
     */
    private static boolean startsOrEndsWithVariable(String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return false;
        }
        if (VARIABLE_PATTERN.matcher(stripped).lookingAt()) {
            return true;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(stripped);
        while (matcher.find()) {
            if (matcher.end() == stripped.length()) {
                return true;
            }
        }
        return false;
    }

    /** 文本里有没有一个 {@code $(name)} 形态的变量。 */
    private static boolean hasVariable(String text) {
        return text != null && VARIABLE_PATTERN.matcher(text).find();
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
            errors.put("buttons", "must contain at most " + MAX_BUTTONS + " buttons");
        }
        long quickReplies = buttons.stream().filter(button -> button.type() == ButtonType.QUICK_REPLY).count();
        long urls = buttons.stream().filter(button -> button.type() == ButtonType.URL).count();
        long phoneNumbers = buttons.stream().filter(button -> button.type() == ButtonType.PHONE_NUMBER).count();
        if (quickReplies > 0 && (urls > 0 || phoneNumbers > 0)) {
            errors.put("buttons", "QUICK_REPLY buttons cannot be combined with URL or PHONE_NUMBER buttons");
        }
        if (urls > MAX_URL_BUTTONS) {
            errors.put("buttons.url", "must contain at most " + MAX_URL_BUTTONS + " URL buttons");
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
