package com.mythix.tcg.product.application;

public class ProductFilterValidationException extends RuntimeException {
    private final String field;

    public ProductFilterValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
