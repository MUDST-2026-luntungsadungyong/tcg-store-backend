package com.mythix.tcg.order.application;

public class CheckoutNotAllowedException extends RuntimeException {
    public CheckoutNotAllowedException() {
        super("The authenticated account cannot checkout");
    }
}
