package com.crmforlogistics.messagecenter.service.contactmemory;

public interface ContactMemoryLlmGateway {
    ContactMemoryModels.LlmOutput generate(ContactMemoryModels.Context context);

    final class GatewayException extends RuntimeException {
        private final String code;
        private final boolean retryable;
        private final String diagnostic;

        public GatewayException(String code, boolean retryable) {
            this(code, retryable, code, null);
        }

        public GatewayException(String code, boolean retryable, String diagnostic, Throwable cause) {
            super(code, cause);
            this.code = code;
            this.retryable = retryable;
            this.diagnostic = diagnostic == null || diagnostic.isBlank() ? code : diagnostic;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }

        public String diagnostic() {
            return diagnostic;
        }
    }
}
