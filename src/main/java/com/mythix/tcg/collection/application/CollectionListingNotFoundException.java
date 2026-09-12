package com.mythix.tcg.collection.application;

public class CollectionListingNotFoundException extends RuntimeException {
    public CollectionListingNotFoundException(Long listingId) {
        super("Listing not found: " + listingId);
    }
}
