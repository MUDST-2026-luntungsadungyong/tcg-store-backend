package com.mythix.tcg.seller.application;

public class SellerRequiredException extends RuntimeException {
    public SellerRequiredException() {
        super("An approved seller account is required");
    }
}
