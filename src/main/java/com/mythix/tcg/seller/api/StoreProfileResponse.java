package com.mythix.tcg.seller.api;

import com.mythix.tcg.seller.domain.SellerProfile;

public record StoreProfileResponse(String sellerId, String shopName, String shopDescription, String phone,
                                   String line, String facebook, String otherContact) {
    public StoreProfileResponse(String sellerId, String shopName, String shopDescription, String phone,
                                String line, String facebook) {
        this(sellerId, shopName, shopDescription, phone, line, facebook, null);
    }
    public static StoreProfileResponse from(SellerProfile profile) {
        return new StoreProfileResponse(String.valueOf(profile.getUser().getId()), profile.getShopName(),
            profile.getShopDescription(), profile.getUser().getPhone(), profile.getUser().getContactLine(),
            profile.getUser().getContactFacebook(), profile.getUser().getContactOther());
    }

    public static StoreProfileResponse publicFrom(SellerProfile profile) {
        return new StoreProfileResponse(String.valueOf(profile.getUser().getId()), profile.getShopName(),
            profile.getShopDescription(), null, null, null, null);
    }
}
