package com.crmforlogistics.messagecenter.service.assistant;

/**
 * 助手链路里<b>可以讲给用户听</b>的失败。
 *
 * <p>与 {@code AiTopicException} 同一思路：只用错误码承载语义，HTTP 状态由
 * {@code GlobalExceptionHandler} 一处映射。把状态码写在抛异常的地方会让「同一个原因在
 * 不同端点给不同状态」这种事情无从审查。
 */
public class AssistantException extends RuntimeException {

    /** 功能未开启或凭据缺失（编排服务未装配）。 */
    public static final String DISABLED = "ASSISTANT_DISABLED";
    /** 模型供应商不可达 / 超时 / 5xx。与「没听懂」必须区分开。 */
    public static final String PROVIDER_UNAVAILABLE = "ASSISTANT_UNAVAILABLE";
    /** 请求本身不合法（超长、结构不对）。 */
    public static final String REQUEST_INVALID = "ASSISTANT_REQUEST_INVALID";
    /** 待确认动作不存在，或存在但不属于当前用户 —— 两者刻意不可区分。 */
    public static final String PENDING_NOT_FOUND = "ASSISTANT_ACTION_NOT_FOUND";
    /** 待确认动作已过期（惰性判定）。 */
    public static final String PENDING_EXPIRED = "ASSISTANT_ACTION_EXPIRED";
    /** 待确认动作已被处理过（已确认或已取消）。 */
    public static final String PENDING_ALREADY_DECIDED = "ASSISTANT_ACTION_ALREADY_DECIDED";

    private final String code;

    public AssistantException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AssistantException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
