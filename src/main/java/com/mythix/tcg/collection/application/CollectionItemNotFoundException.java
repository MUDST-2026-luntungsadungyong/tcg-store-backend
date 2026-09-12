package com.mythix.tcg.collection.application;

public class CollectionItemNotFoundException extends RuntimeException {
    public CollectionItemNotFoundException(Long itemId) {
        super("Collection item " + itemId + " was not found");
    }
}
