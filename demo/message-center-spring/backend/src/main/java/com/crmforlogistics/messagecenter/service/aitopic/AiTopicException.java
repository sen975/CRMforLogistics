package com.crmforlogistics.messagecenter.service.aitopic;

public class AiTopicException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final String diagnostic;

    public AiTopicException(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
        this.diagnostic = code;
    }

    public AiTopicException(String code, boolean retryable, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.retryable = retryable;
        this.diagnostic = code;
    }

    public AiTopicException(String code, boolean retryable, String diagnostic, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.retryable = retryable;
        this.diagnostic = diagnostic;
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public String diagnostic() { return diagnostic; }
}
