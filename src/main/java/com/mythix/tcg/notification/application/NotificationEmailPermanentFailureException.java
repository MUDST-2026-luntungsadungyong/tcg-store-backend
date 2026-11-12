package com.mythix.tcg.notification.application;

public class NotificationEmailPermanentFailureException extends RuntimeException {
    public NotificationEmailPermanentFailureException(String message) {
        super(message);
    }
}
