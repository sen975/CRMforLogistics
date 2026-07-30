package com.crmforlogistics.wecomchatdata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityIdMatcherTest {
    @Test
    void buildBindingAcceptsOnlyTheTwoRegisteredAbilities() {
        AbilityIdMatcher matcher = AbilityIdMatcher.fromBuildBinding();

        assertTrue(matcher.matches("conversation_viewer_sync"));
        assertTrue(matcher.matches("conversation_daily_summary"));
        assertFalse(matcher.matches("other_ability"));
        assertFalse(matcher.matches("session_archive_analysis"));
    }

    @Test
    void acceptsOnlyTheConfiguredAbilityId() {
        AbilityIdMatcher matcher = new AbilityIdMatcher("conversation_viewer_sync");

        assertTrue(matcher.matches("conversation_viewer_sync"));
        assertFalse(matcher.matches("other_ability"));
        assertFalse(matcher.matches(""));
        assertFalse(matcher.matches(null));
    }
}
