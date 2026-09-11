package com.crmforlogistics.messagecenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "contact-memory")
public record ContactMemoryConfig(
        @DefaultValue("50") int maxInboundMessages,
        @DefaultValue("4000") int maxMessageChars,
        @DefaultValue("50000") int maxTotalChars,
        @DefaultValue("20") int maxTopics,
        @DefaultValue("1000") int maxTopicChars,
        @DefaultValue("10") int maxCallTranscripts,
        @DefaultValue("4000") int maxTranscriptChars,
        @DefaultValue("100") int maxFacts,
        @DefaultValue("100") int maxLabels,
        @DefaultValue("100") int maxObservations,
        @DefaultValue("500") int maxObservationChars,
        @DefaultValue("30") int observationTtlDays
) {
    public ContactMemoryConfig {
        requireRange("maxInboundMessages", maxInboundMessages, 1, 500);
        requireRange("maxMessageChars", maxMessageChars, 1, 20_000);
        requireRange("maxTotalChars", maxTotalChars, maxMessageChars, 500_000);
        requireRange("maxTopics", maxTopics, 1, 100);
        requireRange("maxTopicChars", maxTopicChars, 1, 10_000);
        requireRange("maxCallTranscripts", maxCallTranscripts, 1, 50);
        requireRange("maxTranscriptChars", maxTranscriptChars, 1, 20_000);
        requireRange("maxFacts", maxFacts, 1, 500);
        requireRange("maxLabels", maxLabels, 1, 500);
        requireRange("maxObservations", maxObservations, 1, 500);
        requireRange("maxObservationChars", maxObservationChars, 1, 10_000);
        requireRange("observationTtlDays", observationTtlDays, 1, 365);
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be " + minimum + ".." + maximum);
        }
    }
}
