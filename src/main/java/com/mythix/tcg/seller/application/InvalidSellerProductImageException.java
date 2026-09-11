package com.mythix.tcg.seller.application;

/**
 * Raised when an uploaded seller product image is not acceptable.
 *
 * <p>The error code is stable and can be returned by the API layer without
 * exposing storage implementation details.</p>
 */
public class InvalidSellerProductImageException extends RuntimeException {

    private final String code;

    public InvalidSellerProductImageException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
