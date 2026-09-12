package com.mythix.tcg.order.application;

public class CheckoutListingNotAvailableException extends RuntimeException {
    public CheckoutListingNotAvailableException(Long listingId) {
        super("Listing is no longer available: " + listingId);
    }
}
