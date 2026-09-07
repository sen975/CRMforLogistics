package com.crmforlogistics.messagecenter.service.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountCredentialsTest {

    @Test
    void normalizesUsernameWithNfkcTrimAndLowercaseIdentity() {
        AccountCredentials.Username value = AccountCredentials.username("  Agent_01  ");

        assertThat(value.value()).isEqualTo("Agent_01");
        assertThat(value.normalized()).isEqualTo("agent_01");
    }

    @Test
    void rejectsInvalidUsernameShapes() {
        assertThatThrownBy(() -> AccountCredentials.username("_agent"))
                .isInstanceOf(AccountException.class)
                .hasMessage("ACCOUNT_VALIDATION_FAILED");
        assertThatThrownBy(() -> AccountCredentials.username("用户名"))
                .isInstanceOf(AccountException.class)
                .hasMessage("ACCOUNT_VALIDATION_FAILED");
    }

    @Test
    void requiresLetterAndDigitInPassword() {
        AccountCredentials.validatePassword("Example123");

        assertThatThrownBy(() -> AccountCredentials.validatePassword("onlyletters"))
                .isInstanceOf(AccountException.class)
                .hasMessage("ACCOUNT_VALIDATION_FAILED");
        assertThatThrownBy(() -> AccountCredentials.validatePassword("12345678"))
                .isInstanceOf(AccountException.class)
                .hasMessage("ACCOUNT_VALIDATION_FAILED");
    }

    @Test
    void defaultsMissingDisplayNameToUsername() {
        assertThat(AccountCredentials.displayName(null, "Agent_01")).isEqualTo("Agent_01");
        assertThat(AccountCredentials.displayName(" 张三 ", "Agent_01")).isEqualTo("张三");
    }
}
