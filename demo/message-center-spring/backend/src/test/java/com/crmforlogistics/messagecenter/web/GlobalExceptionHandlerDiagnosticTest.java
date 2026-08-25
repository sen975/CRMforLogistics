package com.crmforlogistics.messagecenter.web;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerDiagnosticTest {
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
