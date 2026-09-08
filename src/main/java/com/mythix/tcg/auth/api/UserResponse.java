package com.mythix.tcg.auth.api;

import com.mythix.tcg.user.domain.User;

public record UserResponse(String id, String name, String email, String role, String sellerStatus) {
    public static UserResponse from(User user) {
        return new UserResponse(String.valueOf(user.getId()), user.getFullName(), user.getEmail(), user.getRole(), user.getSellerStatus());
    }
}
