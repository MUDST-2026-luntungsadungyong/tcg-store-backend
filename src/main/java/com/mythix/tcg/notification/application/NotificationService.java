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
    private final NotificationEmailService notificationEmailService;

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

        recipients.values().forEach(seller -> saveNotification(UserNotification.builder()
            .recipient(seller)
            .order(order)
            .type(NotificationTypes.ORDER_RESERVED)
            .title("New card reservation")
            .message("A buyer reserved cards from your listings in order " + order.getOrderNumber() + ".")
            .actionPath(sellerOrderPath(order))
            .build()));
    }

    @Transactional
    public void notifyBuyerOrderPlaced(Order order) {
        saveNotification(orderNotification(order, order.getBuyer(), NotificationTypes.ORDER_PLACED_BUYER,
            "Order placed successfully", "Your order " + order.getOrderNumber() + " was placed successfully.", buyerOrderPath(order)));
    }

    @Transactional
    public void notifyBuyerOrderConfirmed(Order order) {
        saveNotification(orderNotification(order, order.getBuyer(), NotificationTypes.ORDER_CONFIRMED_BUYER,
            "Your order was confirmed", "The seller confirmed order " + order.getOrderNumber() + ".", buyerOrderPath(order)));
    }

    @Transactional
    public void notifySellersOfBuyerCancellation(Order order, String reason) {
        notifySellers(order, NotificationTypes.ORDER_CANCELLED_BUYER, "Order cancelled by buyer",
            "The buyer cancelled order " + order.getOrderNumber() + "." + reasonSuffix(reason));
    }

    @Transactional
    public void notifyBuyerOfSellerCancellation(Order order, String reason) {
        saveNotification(orderNotification(order, order.getBuyer(), NotificationTypes.ORDER_CANCELLED_SELLER,
            "Your order was cancelled", "The seller cancelled order " + order.getOrderNumber() + "." + reasonSuffix(reason), buyerOrderPath(order)));
    }

    @Transactional
    public void notifySellersOfBuyerFulfillment(Order order) {
        notifySellers(order, NotificationTypes.ORDER_FULFILLED_SELLER, "Order marked as received",
            "The buyer marked order " + order.getOrderNumber() + " as received.");
    }

    @Transactional
    public void notifyBuyerOrderSent(Order order) {
        saveNotification(UserNotification.builder()
            .recipient(order.getBuyer())
            .order(order)
            .type(NotificationTypes.ORDER_SENT_BUYER)
            .title("Your order has been sent")
            .message("The seller marked order " + order.getOrderNumber() + " as sent. Tracking number: "
                + order.getTrackingNumber() + (order.getShippingCarrier() == null ? "" : ". Carrier: " + order.getShippingCarrier()))
            .actionPath(buyerOrderPath(order))
            .build());
    }

    @Transactional
    public void notifyAdminsOfSellerApplication(SellerProfile profile, Collection<User> admins) {
        for (User admin : admins) {
            saveNotification(sellerApplicationNotification(
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
        saveNotification(sellerApplicationNotification(
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
        saveNotification(sellerApplicationNotification(
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

    @Transactional
    public void notifyAdminsOfAuctionCancellation(Long auctionId, Collection<User> admins) {
        for (User admin : admins) {
            saveNotification(UserNotification.builder()
                .recipient(admin)
                .type(NotificationTypes.AUCTION_CANCELLATION_SUBMITTED)
                .title("Auction cancellation request")
                .message("Auction #" + auctionId + " has a cancellation request awaiting review.")
                .build());
        }
    }

    @Transactional
    public void notifySellerOfAuctionCancellationDecision(User seller, Long auctionId, boolean approved) {
        saveNotification(UserNotification.builder()
            .recipient(seller)
            .type(NotificationTypes.AUCTION_CANCELLATION_DECIDED)
            .title(approved ? "Auction cancellation approved" : "Auction cancellation rejected")
            .message("An administrator reviewed your cancellation request for auction #" + auctionId + ".")
            .build());
    }

    @Transactional
    public void sendAuctionNotification(User recipient, String type, String title, String message, String actionPath) {
        saveNotification(UserNotification.builder()
            .recipient(recipient)
            .type(type)
            .title(title)
            .message(message)
            .actionPath(actionPath)
            .build());
    }

    @Transactional
    public void sendStockNotification(User recipient, String type, String title, String message, String actionPath, boolean emailEnabled) {
        UserNotification notification = notificationRepository.save(UserNotification.builder()
            .recipient(recipient)
            .type(type)
            .title(title)
            .message(message)
            .actionPath(actionPath)
            .build());
        notificationEmailService.enqueue(notification, emailEnabled);
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

    private void notifySellers(Order order, String type, String title, String message) {
        Map<Long, User> recipients = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            User seller = item.getSeller();
            if (seller != null && !seller.getId().equals(order.getBuyer().getId())) {
                recipients.putIfAbsent(seller.getId(), seller);
            }
        }
        recipients.values().forEach(seller -> saveNotification(orderNotification(
            order, seller, type, title, message, sellerOrderPath(order))));
    }

    private UserNotification orderNotification(Order order, User recipient, String type, String title, String message, String actionPath) {
        return UserNotification.builder()
            .recipient(recipient)
            .order(order)
            .type(type)
            .title(title)
            .message(message)
            .actionPath(actionPath)
            .build();
    }

    private String buyerOrderPath(Order order) {
        return "/orders/" + order.getId();
    }

    private String reasonSuffix(String reason) {
        if (reason == null || reason.isBlank()) return "";
        String trimmed = reason.trim();
        return " Reason: " + trimmed.substring(0, Math.min(400, trimmed.length()));
    }

    private String sellerOrderPath(Order order) {
        return "/workspace/orders?orderId=" + order.getId();
    }

    private UserNotification saveNotification(UserNotification notification) {
        UserNotification saved = notificationRepository.save(notification);
        notificationEmailService.enqueue(saved);
        return saved;
    }

    private UserNotification findOwned(Long recipientId, Long notificationId) {
        return notificationRepository.findByIdAndRecipient_Id(notificationId, recipientId)
            .orElseThrow(NotificationService::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
    }
}
