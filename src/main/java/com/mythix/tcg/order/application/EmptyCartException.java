package com.mythix.tcg.order.application;

public class EmptyCartException extends RuntimeException {
    public EmptyCartException() {
        super("The cart is empty");
    }
}
