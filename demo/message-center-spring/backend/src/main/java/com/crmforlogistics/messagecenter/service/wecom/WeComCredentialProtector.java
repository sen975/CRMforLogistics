package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public final class WeComCredentialProtector {
    private static final int MAX_CREDENTIAL_LENGTH = 512;

    private final CredentialCipher cipher;

    public WeComCredentialProtector(CredentialCipher cipher) {
        this.cipher = cipher;
    }

    public String protectPermanentCode(String value) {
        return encrypt("permanentCode", value);
    }

    public String revealPermanentCode(String envelope) {
        return decrypt("permanentCode", envelope);
    }

    public String protectSecretKey(String value) {
        return encrypt("secretKey", value);
    }

    public String revealSecretKey(String envelope) {
        return decrypt("secretKey", envelope);
    }

    public boolean isEnvelope(String value) {
        try {
            cipher.requireEnvelope(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String encrypt(String key, String value) {
        require(value);
        try {
            return cipher.encrypt(Map.of(key, value));
        } catch (Exception exception) {
            throw new WeComException("WECOM_CREDENTIAL_ENCRYPTION_FAILED", 500,
                    "企业微信凭据加密失败", exception);
        }
    }

    private String decrypt(String key, String envelope) {
        try {
            Map<String, String> values = cipher.decrypt(envelope);
            String value = values.size() == 1 ? values.get(key) : null;
            require(value);
            return value;
        } catch (Exception exception) {
            throw new WeComException("WECOM_CREDENTIAL_DECRYPTION_FAILED", 500,
                    "企业微信凭据解密失败", exception);
        }
    }

    private static void require(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_CREDENTIAL_LENGTH) {
            throw new IllegalArgumentException("WECOM_CREDENTIAL_INVALID");
        }
    }
}
