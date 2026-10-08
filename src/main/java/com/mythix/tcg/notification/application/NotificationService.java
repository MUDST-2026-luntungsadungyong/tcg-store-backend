package com.mythix.tcg.notification.application;

import com.mythix.tcg.notification.api.NotificationResponse;
import com.mythix.tcg.notification.domain.UserNotification;
import com.mythix.tcg.notification.domain.NotificationTypes;
import com.mythix.tcg.notification.persistence.NotificationRepository;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotifications(Long recipientId, Pageable pageable) {
        return notificationRepository
            .findAllByRecipient_IdOrderByCreatedAtDescIdDesc(recipientId, pageable)
            .map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(Long recipientId) {
        return notificationRepository.countByRecipient_IdAndReadAtIsNull(recipientId);
    }

    @Transactional
    public NotificationResponse markRead(Long recipientId, Long notificationId) {
        UserNotification notification = findOwned(recipientId, notificationId);
        if (notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        return NotificationResponse.from(notificationRepository.save(notification));
    }

    @Transactional
    public void delete(Long recipientId, Long notificationId) {
        if (notificationRepository.deleteByIdAndRecipient_Id(notificationId, recipientId) == 0) {
            throw notFound();
        }
    }

    @Transactional
    public void notifySellersOfReservation(Order order) {
        Map<Long, User> recipients = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            User seller = item.getSeller();
            if (seller != null && !seller.getId().equals(order.getBuyer().getId())) {
                recipients.putIfAbsent(seller.getId(), seller);
            }
        }

        recipients.values().forEach(seller -> notificationRepository.save(UserNotification.builder()
            .recipient(seller)
            .order(order)
            .type(NotificationTypes.ORDER_RESERVED)
            .title("New card reservation")
            .message("A buyer reserved cards from your listings in order " + order.getOrderNumber() + ".")
            .build()));
    }

    @Transactional
    public void notifyBuyerOrderSent(Order order) {
        notificationRepository.save(UserNotification.builder()
            .recipient(order.getBuyer())
            .order(order)
            .type(NotificationTypes.ORDER_STATUS_CHANGED)
            .title("Your order has been sent")
            .message("The seller marked order " + order.getOrderNumber() + " as sent.")
            .build());
    }

    @Transactional
    public void notifyAdminsOfSellerApplication(SellerProfile profile, Collection<User> admins) {
        for (User admin : admins) {
            notificationRepository.save(sellerApplicationNotification(
                admin,
                profile,
                NotificationTypes.SELLER_APPLICATION_SUBMITTED,
                "New seller application",
                profile.getShopName() + " submitted a seller application for review."
            ));
        }
    }

    @Transactional
    public void notifySellerApplicationApproved(SellerProfile profile) {
        notificationRepository.save(sellerApplicationNotification(
            profile.getUser(),
            profile,
            NotificationTypes.SELLER_APPLICATION_APPROVED,
            "Seller application approved",
            "Your seller application for " + profile.getShopName() + " was approved."
        ));
    }

    @Transactional
    public void notifySellerApplicationRejected(SellerProfile profile, String reason) {
        String rejectionReason = reason == null || reason.isBlank() ? "No reason provided." : reason;
        notificationRepository.save(sellerApplicationNotification(
            profile.getUser(),
            profile,
            NotificationTypes.SELLER_APPLICATION_REJECTED,
            "Seller application rejected",
            "Your seller application for " + profile.getShopName() + " was rejected. Reason: " + rejectionReason
        ));
    }

    @Transactional
    public void resolveSellerApplicationReviewNotifications(Long sellerProfileId) {
        notificationRepository.resolveSellerApplicationReviewNotifications(sellerProfileId);
    }

    private UserNotification sellerApplicationNotification(
            User recipient, SellerProfile profile, String type, String title, String message) {
        return UserNotification.builder()
            .recipient(recipient)
            .sellerProfile(profile)
            .type(type)
            .title(title)
            .message(message)
            .build();
    }

    private UserNotification findOwned(Long recipientId, Long notificationId) {
        return notificationRepository.findByIdAndRecipient_Id(notificationId, recipientId)
            .orElseThrow(NotificationService::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
    }
}
