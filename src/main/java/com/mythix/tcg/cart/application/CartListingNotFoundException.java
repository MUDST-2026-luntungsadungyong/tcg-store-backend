package com.mythix.tcg.cart.application;

public class CartListingNotFoundException extends RuntimeException {
    public CartListingNotFoundException(Long listingId) {
        super("Listing not found: " + listingId);
    }
}
