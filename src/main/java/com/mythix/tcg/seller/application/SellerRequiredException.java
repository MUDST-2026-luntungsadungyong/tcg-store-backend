package com.mythix.tcg.seller.application;

public class SellerRequiredException extends RuntimeException {
    public SellerRequiredException() {
        super("A verified seller account is required");
    }
}
