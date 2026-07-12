package com.crmforlogistics.emaildemo;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;

import java.util.Date;
import java.util.Locale;

public class MailPrinter {
    public static void print(Message message, String prefix) throws Exception {
        System.out.println(prefix + " From: " + addresses(message.getFrom()));
        System.out.println(prefix + " Subject: " + subject(message));
        Date sentDate = message.getSentDate();
        Date receivedDate = message.getReceivedDate();
        System.out.println(prefix + " Date: " + (receivedDate != null ? receivedDate : sentDate));
        System.out.println(prefix + " Summary: " + summary(message));
        System.out.println();
    }

    public static String subject(Message message) throws Exception {
        return decodeMimeText(message.getSubject() == null ? "" : message.getSubject());
    }

    public static String summary(Message message) throws Exception {
        String text = bodyText(message).replaceAll("\\s+", " ").trim();
        if (text.length() > 240) {
            return text.substring(0, 240) + "...";
        }
        return text;
    }

    public static String bodyText(Message message) throws Exception {
        return normalizeText(extractText(message));
    }

    public static String addresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < addresses.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(address(addresses[i]));
        }
        return builder.toString();
    }

    public static String decodeMimeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return MimeUtility.decodeText(value).trim();
        } catch (Exception e) {
            return value.trim();
        }
    }

    public static String normalizeStoredText(String value) {
        return normalizeText(decodeMimeText(value));
    }

    private static String extractText(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return content == null ? "" : content.toString();
        }
        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            return content == null ? "" : htmlToText(content.toString());
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                builder.append(extractText(multipart.getBodyPart(i))).append(' ');
            }
            return builder.toString();
        }
        return "";
    }

    private static String address(Address address) {
        if (address instanceof InternetAddress internetAddress) {
            String email = internetAddress.getAddress() == null ? "" : internetAddress.getAddress();
            String personal = decodeMimeText(internetAddress.getPersonal());
            if (!personal.isBlank() && !email.isBlank()) {
                return personal + " <" + email + ">";
            }
            return !email.isBlank() ? email : decodeMimeText(address.toString());
        }
        return decodeMimeText(address.toString());
    }

    private static String htmlToText(String html) {
        String text = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("<[^>]+>", " ");
        return decodeHtmlEntities(text);
    }

    private static String normalizeText(String value) {
        return decodeHtmlEntities(value == null ? "" : value)
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String decodeHtmlEntities(String value) {
        if (value == null || value.indexOf('&') < 0) {
            return value == null ? "" : value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '&') {
                out.append(c);
                continue;
            }
            int semi = value.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 12) {
                out.append(c);
                continue;
            }
            String entity = value.substring(i + 1, semi);
            String decoded = decodeEntity(entity);
            if (decoded == null) {
                out.append(c);
                continue;
            }
            out.append(decoded);
            i = semi;
        }
        return out.toString();
    }

    private static String decodeEntity(String entity) {
        if (entity.startsWith("#x") || entity.startsWith("#X")) {
            return codePoint(entity.substring(2), 16);
        }
        if (entity.startsWith("#")) {
            return codePoint(entity.substring(1), 10);
        }
        return switch (entity.toLowerCase(Locale.ROOT)) {
            case "nbsp" -> " ";
            case "lt" -> "<";
            case "gt" -> ">";
            case "amp" -> "&";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "ndash" -> "-";
            case "mdash" -> "-";
            case "hellip" -> "...";
            default -> null;
        };
    }

    private static String codePoint(String value, int radix) {
        try {
            return new String(Character.toChars(Integer.parseInt(value, radix)));
        } catch (Exception e) {
            return null;
        }
    }
}
