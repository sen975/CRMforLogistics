package com.crmforlogistics.messagecenter.service.assistant.mcp;

/**
 * 工具执行失败，且失败原因是<b>可以讲给用户听</b>的。
 *
 * <p>存在的理由是错误码：编排层与前端需要区分「你说的时间我解析不了」与「系统内部出错」，
 * 而靠匹配异常消息文本去区分是最脆的那种做法 —— 改一个措辞就静默失效。
 *
 * <p>码值全部大写常量，与 HTTP 状态无关：工具是传输无关的，把它和某个传输的状态码绑在一起
 * 会在换传输时立刻变成谎言。
 */
public class ToolExecutionException extends RuntimeException {

    /** 工具名不在注册表里。模型编造工具名时走这条。 */
    public static final String UNKNOWN_TOOL = "UNKNOWN_TOOL";
    /** 缺必填参数。 */
    public static final String MISSING_ARGUMENT = "MISSING_ARGUMENT";
    /** 参数存在但不合法（类型、格式、长度、越界字段）。 */
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    /** 格式正确的资源引用不存在或当前用户不可访问；对外不区分两者。 */
    public static final String FORBIDDEN_OR_NOT_FOUND = "FORBIDDEN_OR_NOT_FOUND";
    public static final String ACCESS_DENIED_MESSAGE = "无法访问该资源，请重新选择";
    /** 目标对象不存在，或存在但不属于当前用户 —— 两者刻意不可区分。 */
    public static final String TODO_NOT_FOUND = "TODO_NOT_FOUND";
    /**
     * 这个能力在当前部署里<b>没有装配</b>（如企微未启用、未配 suite-id）。
     *
     * <p>单独一个码，是为了不把它塞进 {@link #INVALID_ARGUMENT}（那会告诉用户「你的参数不对」，
     * 而其实换什么参数都一样）也不塞进 {@link #INTERNAL}（那会说成「系统故障」，
     * 而它其实是一个已知的、可读的部署状态）。三者的指引完全不同：
     * 这个码的处置是「找管理员开功能」，不是「换个说法再试」。
     */
    public static final String UNAVAILABLE = "UNAVAILABLE";
    /**
     * <b>可能已经送出去了，但结果没记全</b> —— 目前只有一处会用它：邮件的 SMTP 接收成功、
     * 本地投递状态却没能落库（{@code EmailSendService} 的 {@code EMAIL_SEND_OUTCOME_UNKNOWN}）。
     *
     * <p>单独一个码而不是并进 {@link #INTERNAL}，是因为它的处置<b>与所有其他失败都相反</b>：
     * 别的失败可以「请稍后再试」，而这一个重试就会真的发出第二封信。
     * 让模型看到 {@code INTERNAL} 它会自己去重试，而用户会收到两封一样的邮件 ——
     * 这种错误必须有一个能被模型读懂的名字。
     */
    public static final String SEND_OUTCOME_UNKNOWN = "SEND_OUTCOME_UNKNOWN";
    /** 未预期的内部错误。不要把底层异常消息泄露给用户。 */
    public static final String INTERNAL = "INTERNAL";

    private final String code;

    public ToolExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ToolExecutionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
