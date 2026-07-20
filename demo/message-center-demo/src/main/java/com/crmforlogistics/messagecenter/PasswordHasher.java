package com.crmforlogistics.messagecenter;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;

import java.util.Arrays;

public interface PasswordHasher {
    String hash(char[] password);
    boolean verify(String encodedHash, char[] password);

    static PasswordHasher argon2id() {
        return new Argon2idPasswordHasher();
    }
}

final class Argon2idPasswordHasher implements PasswordHasher {
    private static final int MEMORY_KIB = 65_536;
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 1;

    @Override
    public String hash(char[] password) {
        if (password == null || password.length == 0) throw new IllegalArgumentException("Password is required");
        Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
        try {
            return argon2.hash(ITERATIONS, MEMORY_KIB, PARALLELISM, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Override
    public boolean verify(String encodedHash, char[] password) {
        if (encodedHash == null || password == null) return false;
        Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
        try {
            return argon2.verify(encodedHash, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
