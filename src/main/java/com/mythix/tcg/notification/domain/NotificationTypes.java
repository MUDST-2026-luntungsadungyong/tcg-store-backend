package com.mythix.tcg.notification.domain;

public final class NotificationTypes {

    public static final String ORDER_RESERVED = "ORDER_RESERVED";
    public static final String ORDER_STATUS_CHANGED = "ORDER_STATUS_CHANGED";
    public static final String SELLER_APPLICATION_SUBMITTED = "SELLER_APPLICATION_SUBMITTED";
    public static final String SELLER_APPLICATION_APPROVED = "SELLER_APPLICATION_APPROVED";
    public static final String SELLER_APPLICATION_REJECTED = "SELLER_APPLICATION_REJECTED";
    public static final String AUCTION_CANCELLATION_SUBMITTED = "AUCTION_CANCELLATION_SUBMITTED";
    public static final String AUCTION_CANCELLATION_DECIDED = "AUCTION_CANCELLATION_DECIDED";
    public static final String AUCTION_OUTBID = "AUCTION_OUTBID";
    public static final String AUCTION_WON = "AUCTION_WON";
    public static final String AUCTION_ENDED_SELLER = "AUCTION_ENDED_SELLER";
    public static final String AUCTION_WATCHLIST_ENDING = "AUCTION_WATCHLIST_ENDING";
    public static final String AUCTION_SECOND_CHANCE = "AUCTION_SECOND_CHANCE";
    public static final String AUCTION_DEFAULTED_WARNING = "AUCTION_DEFAULTED_WARNING";

    private NotificationTypes() {
    }
}
