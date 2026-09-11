package com.crmforlogistics.messagecenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;

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
        @DefaultValue("30") int observationTtlDays,
        @DefaultValue("50") int batchSize,
        @DefaultValue("600") int pollIntervalSeconds,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("60") int leaseSeconds,
        @DefaultValue("900") int maxBackoffSeconds,
        @DefaultValue("0") int startHour,
        @DefaultValue("0") int startMinute,
        @DefaultValue("UTC") String timeZone
) {
    public ContactMemoryConfig(
            int maxInboundMessages,
            int maxMessageChars,
            int maxTotalChars,
            int maxTopics,
            int maxTopicChars,
            int maxCallTranscripts,
            int maxTranscriptChars,
            int maxFacts,
            int maxLabels,
            int maxObservations,
            int maxObservationChars,
            int observationTtlDays) {
        this(maxInboundMessages, maxMessageChars, maxTotalChars, maxTopics, maxTopicChars,
                maxCallTranscripts, maxTranscriptChars, maxFacts, maxLabels, maxObservations,
                maxObservationChars, observationTtlDays, 50, 600, 3, 60, 900, 0, 0, "UTC");
    }

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
        requireRange("batchSize", batchSize, 1, 500);
        requireRange("pollIntervalSeconds", pollIntervalSeconds, 1, 86_400);
        requireRange("maxAttempts", maxAttempts, 1, 3);
        requireRange("leaseSeconds", leaseSeconds, 1, 86_400);
        requireRange("maxBackoffSeconds", maxBackoffSeconds, 1, 86_400);
        requireRange("startHour", startHour, 0, 23);
        requireRange("startMinute", startMinute, 0, 59);
        if (timeZone == null || timeZone.isBlank()) {
            throw new IllegalArgumentException("timeZone must not be blank");
        }
        try {
            ZoneId.of(timeZone);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("timeZone must be a valid zone ID", exception);
        }
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be " + minimum + ".." + maximum);
        }
    }
}
