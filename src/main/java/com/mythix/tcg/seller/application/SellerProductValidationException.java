package com.mythix.tcg.seller.application;

public class SellerProductValidationException extends RuntimeException {
    private final String field;

    public SellerProductValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
