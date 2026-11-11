package com.mythix.tcg.notification.application;

public class NotificationEmailAttachmentTooLargeException extends NotificationEmailPermanentFailureException {
    public NotificationEmailAttachmentTooLargeException(String message) {
        super(message);
    }
}
