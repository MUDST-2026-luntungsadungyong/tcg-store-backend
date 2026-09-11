package com.mythix.tcg.seller.application;

public class SellerProductForbiddenException extends RuntimeException {
    public SellerProductForbiddenException() {
        super("You do not have permission to manage this product");
    }
}
