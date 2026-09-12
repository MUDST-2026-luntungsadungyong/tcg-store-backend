package com.mythix.tcg.collection.application;

public class CollectionUserNotFoundException extends RuntimeException {
    public CollectionUserNotFoundException(Long userId) {
        super("Authenticated user not found: " + userId);
    }
}
