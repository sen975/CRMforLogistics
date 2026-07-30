package com.crmforlogistics.wecomchatdata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class AbilityIdMatcher {
    public static final String VIEWER_SYNC = "conversation_viewer_sync";
    public static final String DAILY_SUMMARY = "conversation_daily_summary";

    private final List<byte[]> expected;

    public AbilityIdMatcher(String... expectedAbilityIds) {
        if (expectedAbilityIds == null || expectedAbilityIds.length == 0) {
            throw new IllegalArgumentException("expected ability ids are required");
        }
        List<byte[]> values = new ArrayList<>(expectedAbilityIds.length);
        for (String abilityId : expectedAbilityIds) {
            if (abilityId == null || abilityId.isBlank() || abilityId.length() > 128) {
                throw new IllegalArgumentException("expected ability id is required");
            }
            values.add(abilityId.getBytes(StandardCharsets.UTF_8));
        }
        expected = List.copyOf(values);
    }

    public static AbilityIdMatcher fromBuildBinding() {
        return new AbilityIdMatcher(VIEWER_SYNC, DAILY_SUMMARY);
    }

    public boolean matches(String abilityId) {
        if (abilityId == null || abilityId.isBlank() || abilityId.length() > 128) return false;
        byte[] actual = abilityId.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (byte[] candidate : expected) {
            matched |= MessageDigest.isEqual(candidate, actual);
        }
        return matched;
    }
}
