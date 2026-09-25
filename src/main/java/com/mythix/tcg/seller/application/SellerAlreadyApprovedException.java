package com.mythix.tcg.seller.application;

public class SellerAlreadyApprovedException extends RuntimeException {
    public SellerAlreadyApprovedException() {
        super("This account already has a seller profile");
    }
}
