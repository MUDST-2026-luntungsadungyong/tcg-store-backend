package com.mythix.tcg.order.application;

public class CheckoutInsufficientStockException extends RuntimeException {
    public CheckoutInsufficientStockException(Long listingId, int availableQuantity) {
        super("Insufficient stock for listing " + listingId + "; available quantity: " + availableQuantity);
    }
}
