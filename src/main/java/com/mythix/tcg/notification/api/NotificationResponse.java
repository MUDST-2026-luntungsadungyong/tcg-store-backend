package com.mythix.tcg.notification.api;

import com.mythix.tcg.notification.domain.UserNotification;

import java.time.LocalDateTime;

public record NotificationResponse(
    Long id,
    String type,
    String title,
    String message,
    Long orderId,
    Long sellerApplicationId,
    LocalDateTime readAt,
    LocalDateTime createdAt
) {
    public static NotificationResponse from(UserNotification notification) {
        return new NotificationResponse(
            notification.getId(),
            notification.getType(),
            notification.getTitle(),
            notification.getMessage(),
            notification.getOrder() == null ? null : notification.getOrder().getId(),
            notification.getSellerProfile() == null ? null : notification.getSellerProfile().getId(),
            notification.getReadAt(),
            notification.getCreatedAt()
        );
    }
}
