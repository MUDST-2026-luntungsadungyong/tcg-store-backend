package com.mythix.tcg.user.api;

import com.mythix.tcg.user.domain.User;

public record ProfileResponse(String id, String name, String email, String phone, String line, String facebook,
                              String role, String sellerStatus) {
    public static ProfileResponse from(User user) {
        return new ProfileResponse(String.valueOf(user.getId()), user.getFullName(), user.getEmail(), user.getPhone(),
            user.getContactLine(), user.getContactFacebook(), user.getRole(), user.getSellerStatus());
    }
}
