package com.mythix.tcg.order.application;

public class OrderStatusValidationException extends RuntimeException {
    private final String field;

    public OrderStatusValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
