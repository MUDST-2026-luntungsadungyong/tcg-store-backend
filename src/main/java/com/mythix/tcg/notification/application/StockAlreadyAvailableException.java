package com.mythix.tcg.notification.application;

public class StockAlreadyAvailableException extends RuntimeException {
    public StockAlreadyAvailableException() {
        super("This card is currently available to purchase");
    }
}
