package com.crmforlogistics.messagecenter.service.account;

import org.springframework.http.HttpStatus;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AccountCredentials {
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$");
    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    private AccountCredentials() {}

    public static Username username(String raw) {
        String value = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFKC).trim();
        if (!USERNAME.matcher(value).matches()) {
            throw validation();
        }
        return new Username(value, value.toLowerCase(Locale.ROOT));
    }

    public static String displayName(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) value = fallback;
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 50) {
            throw validation();
        }
        return value;
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72
                || !LETTER.matcher(password).find() || !DIGIT.matcher(password).find()) {
            throw validation();
        }
    }

    private static AccountException validation() {
        return new AccountException("ACCOUNT_VALIDATION_FAILED", HttpStatus.BAD_REQUEST);
    }

    public record Username(String value, String normalized) {}
}
