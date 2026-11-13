package com.mythix.tcg.notification.application;

/** Renders the HTML alternative for an email's plain-text body. */
public interface EmailContentRenderer {
    String render(String subject, String plainText);
}
