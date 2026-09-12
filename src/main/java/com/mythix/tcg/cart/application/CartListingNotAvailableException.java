package com.mythix.tcg.cart.application;

public class CartListingNotAvailableException extends RuntimeException {
    public CartListingNotAvailableException(Long listingId) {
        super("Listing is not available: " + listingId);
    }
}
