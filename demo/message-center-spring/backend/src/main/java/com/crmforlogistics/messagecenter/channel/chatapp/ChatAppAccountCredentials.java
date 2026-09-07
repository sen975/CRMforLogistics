package com.crmforlogistics.messagecenter.channel.chatapp;

/** Runtime credentials for one owned WhatsApp/CAMS account. */
public record ChatAppAccountCredentials(
        String accessKeyId,
        String accessKeySecret,
        String custSpaceId,
        String chatappFrom,
        String region,
        String endpoint
) {}
