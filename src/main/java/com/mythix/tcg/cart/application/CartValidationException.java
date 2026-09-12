package com.mythix.tcg.cart.application;

public class CartValidationException extends RuntimeException {
    private final String field;

    public CartValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
