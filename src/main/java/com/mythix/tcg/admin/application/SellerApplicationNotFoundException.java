package com.mythix.tcg.admin.application;

public class SellerApplicationNotFoundException extends RuntimeException {
    public SellerApplicationNotFoundException() {
        super("Seller application was not found");
    }
}
