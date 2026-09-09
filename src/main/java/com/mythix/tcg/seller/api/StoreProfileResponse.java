package com.mythix.tcg.seller.api;

import com.mythix.tcg.seller.domain.SellerProfile;

public record StoreProfileResponse(String sellerId, String shopName, String shopDescription, String phone,
                                   String line, String facebook) {
    public static StoreProfileResponse from(SellerProfile profile) {
        return new StoreProfileResponse(String.valueOf(profile.getUser().getId()), profile.getShopName(),
            profile.getShopDescription(), profile.getContactPhone(), profile.getContactLine(), profile.getContactFacebook());
    }
}
