package com.mythix.tcg.collection.application;

public class PublicCollectionPrivateException extends RuntimeException {

    public PublicCollectionPrivateException(Long userId) {
        super("Collection for user " + userId + " is private.");
    }
}
