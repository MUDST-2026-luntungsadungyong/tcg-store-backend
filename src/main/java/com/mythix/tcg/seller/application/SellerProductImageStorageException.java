package com.mythix.tcg.seller.application;

/**
 * Raised when a valid product image cannot be persisted or removed.
 */
public class SellerProductImageStorageException extends RuntimeException {

    public SellerProductImageStorageException(String message) {
        super(message);
    }

    public SellerProductImageStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
