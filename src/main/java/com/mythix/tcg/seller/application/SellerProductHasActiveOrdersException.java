package com.mythix.tcg.seller.application;

public class SellerProductHasActiveOrdersException extends RuntimeException {
    public SellerProductHasActiveOrdersException(long activeOrders) {
        super("This product cannot be deleted because it belongs to " + activeOrders + " active order(s)");
    }
}
