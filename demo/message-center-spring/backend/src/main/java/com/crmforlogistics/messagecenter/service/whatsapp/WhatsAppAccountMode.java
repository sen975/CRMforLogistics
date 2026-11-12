package com.crmforlogistics.messagecenter.service.whatsapp;

/** Identifies the template domain owned by a WhatsApp/ChatApp account. */
public final class WhatsAppAccountMode {
    private WhatsAppAccountMode() {
    }

    public static boolean isBusinessApp(String mode) {
        return "EMPLOYEE_BUSINESS_APP".equalsIgnoreCase(mode)
                || "BUSINESS_APP_COEXISTENCE".equalsIgnoreCase(mode);
    }
}
