package com.mythix.tcg.seller.application;

public class SellerProductMustBeHiddenException extends RuntimeException {
    public SellerProductMustBeHiddenException() {
        super("Hide this product before deleting it");
    }
}
