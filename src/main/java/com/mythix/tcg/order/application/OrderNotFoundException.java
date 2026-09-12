package com.mythix.tcg.order.application;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(Long orderId) {
        super("Order " + orderId + " was not found");
    }

    public OrderNotFoundException(String message) {
        super(message);
    }
}
