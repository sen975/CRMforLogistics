package com.crmforlogistics.messagecenter.web;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerDiagnosticTest {
    @Test
    void requestBindingDiagnosticIdentifiesMalformedJsonFieldWithoutLeakingValue() {
        InvalidFormatException failure = InvalidFormatException.from(
                null, "invalid uuid", "secret-value", java.util.UUID.class);
        failure.prependPath("OrderRequest", "sourceId");

        String diagnostic = GlobalExceptionHandler.requestBindingDiagnostic(failure);

        assertThat(diagnostic).contains("path=sourceId");
        assertThat(diagnostic).contains("targetType=UUID");
        assertThat(diagnostic).doesNotContain("secret-value");
    }

    @Test
    void safeDiagnosticContainsTypesAndApplicationFramesButNotExceptionMessages() {
        SQLException cause = new SQLException("permanentCode=must-not-leak", "42703", 0);
        IllegalStateException failure = new IllegalStateException("code=must-not-leak", cause);

        GlobalExceptionHandler.SafeDiagnostic diagnostic =
                GlobalExceptionHandler.safeDiagnostic(failure);

        assertThat(diagnostic.errorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(diagnostic.rootErrorType()).isEqualTo(SQLException.class.getName());
        assertThat(diagnostic.sqlState()).isEqualTo("42703");
        assertThat(diagnostic.sqlErrorCode()).isZero();
        assertThat(diagnostic.applicationFrames())
                .anyMatch(frame -> frame.contains("GlobalExceptionHandlerDiagnosticTest"));
        assertThat(String.join(" ", diagnostic.applicationFrames()))
                .doesNotContain("must-not-leak", "permanentCode", "code=");
    }
}
