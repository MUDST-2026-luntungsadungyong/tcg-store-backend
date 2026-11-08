package com.mythix.tcg.auction.application;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AuctionException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private AuctionException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static AuctionException invalid(String message) {
        return new AuctionException(HttpStatus.BAD_REQUEST, "AUCTION_VALIDATION", message);
    }

    public static AuctionException missing(String message) {
        return new AuctionException(HttpStatus.NOT_FOUND, "AUCTION_NOT_FOUND", message);
    }

    public static AuctionException conflict(String message) {
        return new AuctionException(HttpStatus.CONFLICT, "AUCTION_CONFLICT", message);
    }

    public static AuctionException notFound(String message) {
        return missing(message);
    }

    public static AuctionException forbidden(String message) {
        return new AuctionException(HttpStatus.FORBIDDEN, "AUCTION_FORBIDDEN", message);
    }
}
