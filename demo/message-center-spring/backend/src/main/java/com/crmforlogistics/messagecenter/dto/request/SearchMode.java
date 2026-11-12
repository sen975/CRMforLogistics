package com.crmforlogistics.messagecenter.dto.request;

import java.util.Locale;

/** 列表搜索的匹配范围：联系人字段，或联系人标签。 */
public enum SearchMode {
    CONTACT,
    TAG;

    public static SearchMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CONTACT;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "contact" -> CONTACT;
            case "tag" -> TAG;
            default -> throw new IllegalArgumentException("CONTACT_SEARCH_MODE_INVALID");
        };
    }

    public boolean isTag() {
        return this == TAG;
    }
}
