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
    /** 目标对象不存在，或存在但不属于当前用户 —— 两者刻意不可区分。 */
    public static final String TODO_NOT_FOUND = "TODO_NOT_FOUND";
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
