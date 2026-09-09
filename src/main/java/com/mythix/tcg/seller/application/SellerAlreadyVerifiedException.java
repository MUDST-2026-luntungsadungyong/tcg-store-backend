package com.mythix.tcg.seller.application;

public class SellerAlreadyVerifiedException extends RuntimeException {
    public SellerAlreadyVerifiedException() {
        super("This account already has a seller profile");
    }
}
