package com.mythix.tcg.notification.application;

import com.mythix.tcg.notification.domain.NotificationEmail;
import com.mythix.tcg.notification.domain.NotificationTypes;
import com.mythix.tcg.notification.domain.UserNotification;
import com.mythix.tcg.notification.persistence.NotificationEmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationEmailService {
    private static final Set<String> EMAIL_TYPES = Set.of(
        NotificationTypes.ORDER_RESERVED, NotificationTypes.ORDER_PLACED_BUYER,
        NotificationTypes.ORDER_CANCELLED_BUYER, NotificationTypes.ORDER_CANCELLED_SELLER,
        NotificationTypes.ORDER_SENT_BUYER,
        NotificationTypes.SELLER_APPLICATION_APPROVED, NotificationTypes.SELLER_APPLICATION_REJECTED,
        NotificationTypes.AUCTION_CANCELLATION_DECIDED, NotificationTypes.AUCTION_OUTBID,
        NotificationTypes.AUCTION_WON, NotificationTypes.AUCTION_ENDED_SELLER,
        NotificationTypes.AUCTION_SECOND_CHANCE, NotificationTypes.AUCTION_DEFAULTED_WARNING,
        NotificationTypes.AUCTION_DISPUTE_OPENED, NotificationTypes.AUCTION_DISPUTE_DECIDED
    );
    private final NotificationEmailRepository outbox;
    private final SensitiveMailCipher cipher;
    private final NotificationEmailDeliveryWorker deliveryWorker;

    @Value("${app.mail.enabled:false}")
    private boolean enabled;
    @Value("${app.mail.from:}")
    private String from;
    @Value("${app.mail.base-url:http://localhost:5173}")
    private String baseUrl;

    @Transactional
    public void enqueueSensitive(String recipient, String subject, String body, LocalDateTime expiresAt) {
        NotificationEmail email = new NotificationEmail();
        email.setRecipientEmail(recipient);
        email.setSubject(subject);
        email.setBody(cipher.encrypt(body));
        email.setSensitive(true);
        email.setExpiresAt(expiresAt);
        email.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC));
        outbox.save(email);
    }

    @Transactional
    public void enqueue(UserNotification notification) {
        if (!EMAIL_TYPES.contains(notification.getType()) || notification.getRecipient() == null
                || notification.getRecipient().getEmail() == null || notification.getRecipient().getEmail().isBlank()) {
            return;
        }
        if (NotificationTypes.AUCTION_ENDED_SELLER.equals(notification.getType())
                && (notification.getActionPath() == null || !notification.getActionPath().contains("orderId="))) return;
        if (isDispute(notification.getType()) && !isDisputePath(notification.getActionPath())) return;

        NotificationEmail email = new NotificationEmail();
        email.setNotificationId(notification.getId());
        email.setRecipientEmail(notification.getRecipient().getEmail());
        email.setSubject("Mythix: " + notification.getTitle());
        email.setBody(buildBody(notification));
        email.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC));
        if (NotificationTypes.ORDER_SENT_BUYER.equals(notification.getType()) && notification.getOrder() != null) {
            email.setAttachmentOrderId(notification.getOrder().getId());
            email.setAttachmentType("application/pdf");
            email.setAttachmentStatusSnapshot("SENT");
        }
        outbox.save(email);
    }

    private String buildBody(UserNotification notification) {
        StringBuilder body = new StringBuilder(NotificationTypes.ORDER_SENT_BUYER.equals(notification.getType())
            ? "The seller marked your order as sent." : notification.getMessage());
        if (notification.getOrder() != null) {
            body.append("\n\nOrder: ").append(notification.getOrder().getOrderNumber());
            if (NotificationTypes.ORDER_SENT_BUYER.equals(notification.getType())) {
                body.append("\nTracking number: ").append(notification.getOrder().getTrackingNumber());
                if (notification.getOrder().getShippingCarrier() != null) body.append("\nCarrier: ").append(notification.getOrder().getShippingCarrier());
            }
        }
        String path = notification.getActionPath();
        if (path == null && notification.getOrder() != null) {
            path = switch (notification.getType()) {
                case NotificationTypes.ORDER_RESERVED, NotificationTypes.ORDER_CANCELLED_SELLER, NotificationTypes.ORDER_FULFILLED_SELLER -> "/workspace/orders";
                default -> "/orders/" + notification.getOrder().getId();
            };
        }
        if (path == null) {
            path = switch (notification.getType()) {
                case NotificationTypes.SELLER_APPLICATION_APPROVED, NotificationTypes.SELLER_APPLICATION_REJECTED -> "/profile";
                case NotificationTypes.AUCTION_CANCELLATION_DECIDED -> "/workspace/auctions";
                default -> null;
            };
        }
        if (path != null && path.startsWith("/") && !path.startsWith("//")) {
            body.append("\n\nView details: ").append(baseUrl.replaceAll("/+$", "")).append(path);
        }
        body.append("\n\nMythix TCG");
        return body.toString();
    }

    private boolean isDispute(String type) {
        return NotificationTypes.AUCTION_DISPUTE_OPENED.equals(type)
            || NotificationTypes.AUCTION_DISPUTE_DECIDED.equals(type);
    }

    private boolean isDisputePath(String path) {
        return path != null && path.matches("/auction-disputes\\?disputeId=[1-9][0-9]*");
    }

    @Scheduled(fixedDelayString = "${app.mail.poll-ms:30000}")
    public void deliverDue() {
        if (!enabled || from.isBlank()) return;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (NotificationEmail email : outbox.findDue(now, PageRequest.of(0, 50))) {
            LocalDateTime claimedAt = LocalDateTime.now(ZoneOffset.UTC);
            if (outbox.claim(email.getId(), claimedAt, claimedAt.plusMinutes(2)) != 1) continue;
            try {
                if (email.getExpiresAt() != null && !email.getExpiresAt().isAfter(claimedAt)) {
                    outbox.markSent(email.getId(), claimedAt);
                    continue;
                }
                deliveryWorker.deliver(email.getId(), from);
            } catch (Exception error) {
                if (error instanceof NotificationEmailPermanentFailureException) {
                    String detail = error.getMessage();
                    outbox.markFailed(email.getId(), LocalDateTime.now(ZoneOffset.UTC), detail.substring(0, Math.min(500, detail.length())));
                    log.error("Email delivery permanently failed for notification {}: {}", email.getNotificationId(), detail);
                    continue;
                }
                int minutes = Math.min(60, 1 << Math.min(email.getAttempts(), 5));
                String detail = error.getClass().getSimpleName() + ": " + error.getMessage();
                outbox.reschedule(email.getId(), LocalDateTime.now(ZoneOffset.UTC).plusMinutes(minutes),
                    detail.substring(0, Math.min(500, detail.length())));
                log.warn("Email delivery failed for notification {}: {}", email.getNotificationId(), detail);
            }
        }
    }
}
