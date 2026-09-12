package com.mythix.tcg.collection.application;

public class CardNotFoundException extends RuntimeException {
    public CardNotFoundException(String cardId) {
        super("Card " + cardId + " was not found");
    }
}
