package com.mythix.tcg.seller.api;

import com.mythix.tcg.seller.domain.SellerProfile;

import java.time.LocalDateTime;

public record SellerVerificationResponse(String id, String shopName, String shopDescription, String phone, String line,
                                         String facebook, String sellerStatus, LocalDateTime verifiedAt) {
    public static SellerVerificationResponse from(SellerProfile profile) {
        return new SellerVerificationResponse(String.valueOf(profile.getId()), profile.getShopName(),
            profile.getShopDescription(), profile.getContactPhone(), profile.getContactLine(),
            profile.getContactFacebook(), profile.getUser().getSellerStatus(), profile.getVerifiedAt());
    }
}
