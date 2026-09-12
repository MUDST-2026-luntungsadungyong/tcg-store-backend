package com.mythix.tcg.seller.application;

public class SellerProductNotFoundException extends RuntimeException {
    public SellerProductNotFoundException(Long productId) {
        super("Product " + productId + " was not found");
    }
}
