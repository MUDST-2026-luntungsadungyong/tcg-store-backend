package com.mythix.tcg.cart.application;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(Long listingId, int availableQuantity) {
        super("Insufficient stock for listing " + listingId + "; available quantity: " + availableQuantity);
    }
}
