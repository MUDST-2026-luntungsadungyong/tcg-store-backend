package com.mythix.tcg.cart.application;

public class CartItemNotFoundException extends RuntimeException {
    public CartItemNotFoundException(Long listingId) {
        super("Cart item not found for listing " + listingId);
    }
}
