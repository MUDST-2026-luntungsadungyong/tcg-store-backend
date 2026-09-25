package com.mythix.tcg.admin.application;

public class SellerApplicationStateException extends RuntimeException {
    public SellerApplicationStateException() {
        super("Only pending seller applications can be approved");
    }
}
