package com.crmforlogistics.messagecenter.channel.email;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import org.springframework.web.util.HtmlUtils;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class EmailMimeParser {
    public record ParsedEmail(String bodyText, List<EmailAttachmentInput> attachments, List<String> errorCodes) {}

    public ParsedEmail parse(Part root) {
        var attachments = new ArrayList<EmailAttachmentInput>();
        var errors = new ArrayList<String>();
        String body = extract(root, attachments, errors);
        return new ParsedEmail(body == null ? "" : body.trim(), List.copyOf(attachments), List.copyOf(errors));
    }

    private String extract(Part part, List<EmailAttachmentInput> attachments, List<String> errors) {
        try {
            if (isOrdinaryAttachment(part)) {
                String name = part.getFileName();
                String mime = part.getContentType();
                attachments.add(new EmailAttachmentInput(name == null ? "attachment" : name,
                        mime == null ? "application/octet-stream" : mime.split(";", 2)[0],
                        part.getSize(), () -> {
                            try {
                                return part.getInputStream();
                            } catch (Exception ex) {
                                throw new java.io.IOException("Unable to open MIME attachment", ex);
                            }
                        }));
                return "";
            }
            if (part.isMimeType("text/plain")) {
                Object content = part.getContent();
                return content == null ? "" : content.toString();
            }
            if (part.isMimeType("text/html")) {
                Object content = part.getContent();
                return content == null ? "" : htmlToText(content.toString());
            }
            if (part.isMimeType("multipart/alternative")) {
                Multipart multipart = (Multipart) part.getContent();
                String plain = "";
                String html = "";
                for (int i = 0; i < multipart.getCount(); i++) {
                    var child = multipart.getBodyPart(i);
                    String candidate = extract(child, attachments, errors);
                    if (child.isMimeType("text/plain") && plain.isBlank()) plain = candidate;
                    if (child.isMimeType("text/html") && html.isBlank()) html = candidate;
                }
                return !plain.isBlank() ? plain : html;
            }
            if (part.isMimeType("multipart/*")) {
                Multipart multipart = (Multipart) part.getContent();
                StringBuilder body = new StringBuilder();
                for (int i = 0; i < multipart.getCount(); i++) {
                    String candidate = extract(multipart.getBodyPart(i), attachments, errors);
                    if (!candidate.isBlank()) {
                        if (body.length() > 0) body.append('\n');
                        body.append(candidate);
                    }
                }
                return body.toString();
            }
        } catch (Exception ex) {
            errors.add("EMAIL_ATTACHMENT_READ_FAILED:" + ex.getClass().getSimpleName() + ":" + ex.getMessage());
        }
        return "";
    }

    private static boolean isOrdinaryAttachment(Part part) throws Exception {
        String disposition = part.getDisposition();
        String[] contentIds = part.getHeader("Content-ID");
        String contentId = contentIds == null || contentIds.length == 0 ? null : contentIds[0];
        if (Part.INLINE.equalsIgnoreCase(disposition) || (contentId != null && !contentId.isBlank())) return false;
        String name = part.getFileName();
        return Part.ATTACHMENT.equalsIgnoreCase(disposition) || (name != null && !name.isBlank());
    }

    private static String htmlToText(String html) {
        return HtmlUtils.htmlUnescape(html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("<[^>]+>", " "))
                .replace('\u00A0', ' ');
    }
}
