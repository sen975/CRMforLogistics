package com.crmforlogistics.messagecenter.service.whatsapp.template;

public final class TemplateDisplayName {
    private TemplateDisplayName() {
    }

    public static String format(String officialName, String remark) {
        String name = officialName == null ? "" : officialName;
        String local = remark == null ? "" : remark.trim();
        return local.isEmpty() ? name : local + "（" + name + "）";
    }
}
