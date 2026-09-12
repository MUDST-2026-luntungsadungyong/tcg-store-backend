package com.mythix.tcg.collection.application;

public class PublicCollectionUserNotFoundException extends RuntimeException {

    public PublicCollectionUserNotFoundException(Long userId) {
        super("Public collection user not found: " + userId);
    }
}
