package com.mythix.tcg.notification.application;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds a compact, email-client-safe Mythix HTML alternative from the outbox's plain-text content. */
@Component
public class BrandedEmailRenderer {
    private static final String FONT = "'IBM Plex Sans Thai', Arial, Helvetica, sans-serif";

    public String render(String subject, String plainText) {
        String safeSubject = escape(subject == null ? "Mythix update" : subject);
        List<String> lines = (plainText == null ? "" : plainText).lines().toList();
        List<String> paragraphs = new ArrayList<>();
        List<Detail> details = new ArrayList<>();
        String ctaUrl = null;
        String ctaLabel = null;
        StringBuilder paragraph = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                flush(paragraph, paragraphs);
                continue;
            }
            if (line.startsWith("View details: ")) {
                flush(paragraph, paragraphs);
                ctaUrl = validHttpUrl(line.substring("View details: ".length()).trim());
                if (ctaUrl != null) ctaLabel = "View details";
            } else if (line.matches("(?i)(Order|Tracking number|Carrier):\\s*.*")) {
                flush(paragraph, paragraphs);
                int separator = line.indexOf(':');
                details.add(new Detail(line.substring(0, separator), line.substring(separator + 1).trim()));
            } else if (isStandaloneUrl(line)) {
                flush(paragraph, paragraphs);
                String url = validHttpUrl(line);
                if (url != null) {
                    ctaUrl = url;
                    ctaLabel = "Reset password";
                } else {
                    append(paragraph, line);
                }
            } else if (line.equalsIgnoreCase("Mythix TCG")) {
                flush(paragraph, paragraphs);
            } else {
                append(paragraph, line);
            }
        }
        flush(paragraph, paragraphs);

        String intro = paragraphs.isEmpty() ? "You have a new update from Mythix TCG." : paragraphs.get(0);
        String category = category(subject);
        String accent = accent(subject);
        StringBuilder html = new StringBuilder(2048);
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            .append("<title>").append(safeSubject).append("</title></head>")
            .append("<body style=\"margin:0;padding:0;background-color:#0B141F;color:#F3F4F6;font-family:").append(FONT).append(";\">")
            .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:#0B141F;width:100%;\"><tr><td align=\"center\" style=\"padding:32px 14px;\">")
            .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%;max-width:600px;border-collapse:separate;\">")
            .append("<tr><td style=\"padding:0 4px 20px;\"><div style=\"font-size:12px;line-height:18px;letter-spacing:2px;font-weight:700;color:#BFA659;\">MYTHIX <span style=\"color:#9CA3AF;font-weight:400;letter-spacing:1px;\">TCG STORE</span></div></td></tr>")
            .append("<tr><td style=\"background-color:#1B2336;border:1px solid #303B52;border-radius:14px;padding:32px 30px;\">")
            .append("<div style=\"font-size:12px;line-height:18px;letter-spacing:1.4px;text-transform:uppercase;color:").append(accent).append(";font-weight:700;padding-bottom:10px;\">").append(escape(category)).append("</div>")
            .append("<h1 style=\"margin:0 0 22px;font-size:24px;line-height:32px;color:#F3F4F6;font-weight:700;\">").append(safeSubject).append("</h1>")
            .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"background-color:#212B42;border-left:3px solid ").append(accent).append(";border-radius:6px;padding:14px 16px;color:#D1D5DB;font-size:15px;line-height:23px;\">")
            .append(intro).append("</td></tr></table>");

        if (!details.isEmpty()) {
            html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin-top:22px;border-collapse:collapse;\">");
            for (Detail detail : details) {
                html.append("<tr><td style=\"padding:11px 4px;border-bottom:1px solid #303B52;color:#9CA3AF;font-size:13px;line-height:19px;width:38%;\">")
                    .append(escape(detail.label)).append("</td><td style=\"padding:11px 4px;border-bottom:1px solid #303B52;color:#F3F4F6;font-size:14px;line-height:20px;word-break:break-word;\">")
                    .append(escape(detail.value)).append("</td></tr>");
            }
            html.append("</table>");
        }

        for (int i = 1; i < paragraphs.size(); i++) {
            html.append("<p style=\"margin:18px 0 0;color:#D1D5DB;font-size:14px;line-height:22px;\">").append(paragraphs.get(i)).append("</p>");
        }
        if (ctaUrl != null) {
            html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin-top:26px;\"><tr><td bgcolor=\"#72A8FF\" style=\"border-radius:7px;\">")
                .append("<a href=\"").append(escapeAttribute(ctaUrl)).append("\" style=\"display:inline-block;padding:13px 20px;color:#0B141F;text-decoration:none;font-size:14px;line-height:20px;font-weight:700;\">")
                .append(escape(ctaLabel == null ? "View details" : ctaLabel)).append("</a></td></tr></table>");
        }
        html.append("</td></tr><tr><td style=\"padding:20px 4px 0;color:#9CA3AF;font-size:12px;line-height:19px;\">Mythix TCG<br>You're receiving this message because of activity on your Mythix account.</td></tr>")
            .append("</table></td></tr></table></body></html>");
        return html.toString();
    }

    private static void append(StringBuilder paragraph, String line) {
        if (!paragraph.isEmpty()) paragraph.append(' ');
        paragraph.append(escape(line));
    }

    private static void flush(StringBuilder paragraph, List<String> paragraphs) {
        if (!paragraph.isEmpty()) {
            paragraphs.add(paragraph.toString());
            paragraph.setLength(0);
        }
    }

    private static boolean isStandaloneUrl(String value) {
        return value.regionMatches(true, 0, "https://", 0, 8) || value.regionMatches(true, 0, "http://", 0, 7);
    }

    private static String validHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.toLowerCase(Locale.ROOT).equals("https") || scheme.toLowerCase(Locale.ROOT).equals("http"))
                    || uri.getHost() == null || uri.getUserInfo() != null) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeAttribute(String value) {
        return escape(value).replace("`", "&#96;");
    }

    private static String category(String subject) {
        String value = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        if (value.contains("password") || value.contains("security")) return "Security";
        if (value.contains("order")) return "Order update";
        if (value.contains("seller application")) return "Seller application";
        if (value.contains("auction") || value.contains("dispute")) return "Auction & dispute";
        return "Mythix update";
    }

    private static String accent(String subject) {
        String value = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        if (value.contains("approved") || value.contains("won") || value.contains("sent")) return "#4CC987";
        if (value.contains("cancelled") || value.contains("rejected") || value.contains("warning") || value.contains("defaulted")) return "#FFA5A5";
        return "#72A8FF";
    }

    private record Detail(String label, String value) { }
}
