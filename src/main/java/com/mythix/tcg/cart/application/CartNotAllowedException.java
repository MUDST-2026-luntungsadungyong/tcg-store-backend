package com.mythix.tcg.cart.application;

public class CartNotAllowedException extends RuntimeException {
    public CartNotAllowedException() {
        super("The authenticated account cannot use a cart");
    }
}
