package com.crmforlogistics.messagecenter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ChannelAccountService {
    private static final ValidationResult NOT_FOUND = new ValidationResult(
            false, "CHANNEL_ACCOUNT_NOT_FOUND", "Channel account not found");
    private static final ValidationResult DECRYPTION_FAILED = new ValidationResult(
            false, "CREDENTIAL_DECRYPTION_FAILED", "Unable to read channel credentials");
    private static final ValidationResult VALIDATION_FAILED = new ValidationResult(
            false, "CHANNEL_VALIDATION_FAILED", "Unable to validate channel credentials");

    private final ChannelAccountRepository repository;
    private final CredentialCipher cipher;

    public ChannelAccountService(ChannelAccountRepository repository, CredentialCipher cipher) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    public ValidationResult validate(UUID accountId, CredentialValidator validator) throws Exception {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(validator, "validator");
        Optional<ChannelAccount> account = repository.find(accountId);
        if (account.isEmpty()) {
            return NOT_FOUND;
        }

        Map<String, String> decrypted;
        try {
            decrypted = new LinkedHashMap<>(cipher.decrypt(account.get().encryptedConfig()));
        } catch (CredentialCipher.CredentialDecryptionException exception) {
            return DECRYPTION_FAILED;
        }

        try {
            ValidationResult result = validator.validate(
                    account.get(), Collections.unmodifiableMap(decrypted));
            if (result == null) {
                return VALIDATION_FAILED;
            }
            return redactSecrets(result, decrypted);
        } catch (Exception exception) {
            return VALIDATION_FAILED;
        } finally {
            decrypted.replaceAll((key, value) -> "");
            decrypted.clear();
        }
    }

    private static ValidationResult redactSecrets(ValidationResult result,
                                                   Map<String, String> decryptedSecrets) {
        String code = result.code();
        if (code == null || !code.matches("[A-Z0-9_]{1,100}")) {
            code = result.valid() ? "VALID" : "INVALID_CREDENTIALS";
        }
        String message = result.message() == null ? "" : result.message();
        for (String secret : decryptedSecrets.values()) {
            if (secret != null && !secret.isEmpty()) {
                message = message.replace(secret, "[REDACTED]");
            }
        }
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        return new ValidationResult(result.valid(), code, message);
    }
}
