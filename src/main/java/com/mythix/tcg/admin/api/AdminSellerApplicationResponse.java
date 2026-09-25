package com.mythix.tcg.admin.api;

import com.mythix.tcg.seller.domain.SellerProfile;

import java.time.LocalDateTime;

public record AdminSellerApplicationResponse(
    String id,
    String userId,
    String applicantName,
    String applicantEmail,
    String shopName,
    String shopDescription,
    String phone,
    String line,
    String facebook,
    String sellerStatus,
    LocalDateTime requestedAt
) {
    public static AdminSellerApplicationResponse from(SellerProfile profile) {
        return new AdminSellerApplicationResponse(
            String.valueOf(profile.getId()), String.valueOf(profile.getUser().getId()), profile.getUser().getFullName(),
            profile.getUser().getEmail(), profile.getShopName(), profile.getShopDescription(), profile.getUser().getPhone(),
            profile.getUser().getContactLine(), profile.getUser().getContactFacebook(), profile.getUser().getSellerStatus(), profile.getRequestedAt()
        );
    }
}
