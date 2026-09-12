package com.mythix.tcg.collection.application;

public class CollectionValidationException extends RuntimeException {
    private final String field;

    public CollectionValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
