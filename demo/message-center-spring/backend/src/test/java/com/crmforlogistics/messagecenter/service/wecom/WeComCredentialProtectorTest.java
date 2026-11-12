package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeComCredentialProtectorTest {

    @Test
    void protectsAndRevealsTypedSecrets() {
        var cipher = CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
        var protector = new WeComCredentialProtector(cipher);

        String permanent = protector.protectPermanentCode("permanent-value");
        String secret = protector.protectSecretKey("message-secret");

        assertThat(permanent).doesNotContain("permanent-value");
        assertThat(secret).doesNotContain("message-secret");
        assertThat(protector.revealPermanentCode(permanent)).isEqualTo("permanent-value");
        assertThat(protector.revealSecretKey(secret)).isEqualTo("message-secret");
    }

    @Test
    void rejectsEnvelopeWithTheWrongCredentialTypeWithoutLeakingItsValue() {
        var cipher = CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
        var protector = new WeComCredentialProtector(cipher);
        String secret = protector.protectSecretKey("message-secret");

        assertThatThrownBy(() -> protector.revealPermanentCode(secret))
                .isInstanceOf(WeComException.class)
                .hasMessageNotContaining("message-secret");
    }
}
