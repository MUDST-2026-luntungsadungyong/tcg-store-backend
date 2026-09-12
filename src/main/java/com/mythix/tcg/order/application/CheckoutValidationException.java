package com.mythix.tcg.order.application;

public class CheckoutValidationException extends RuntimeException {
    private final String field;

    public CheckoutValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
