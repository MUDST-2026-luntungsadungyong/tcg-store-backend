package com.mythix.tcg.notification.application;

import com.mythix.tcg.notification.domain.NotificationEmail;
import com.mythix.tcg.notification.domain.UserNotification;
import com.mythix.tcg.notification.persistence.NotificationEmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
        "ORDER_RESERVED", "ORDER_PLACED_BUYER", "ORDER_CANCELLED_BUYER",
        "ORDER_CANCELLED_SELLER", "ORDER_SENT_BUYER",
        "SELLER_APPLICATION_APPROVED", "SELLER_APPLICATION_REJECTED",
        "AUCTION_CANCELLATION_DECIDED", "AUCTION_OUTBID", "AUCTION_WON",
        "AUCTION_ENDED_SELLER", "AUCTION_SECOND_CHANCE", "AUCTION_DEFAULTED_WARNING"
    );

    private final NotificationEmailRepository outbox;
    private final JavaMailSender mailSender;

    @Value("${app.mail.enabled:false}")
    private boolean enabled;
    @Value("${app.mail.from:}")
    private String from;
    @Value("${app.mail.base-url:http://localhost:5173}")
    private String baseUrl;

    @Transactional
    public void enqueue(UserNotification notification) {
        if (!EMAIL_TYPES.contains(notification.getType()) || notification.getRecipient() == null
                || notification.getRecipient().getEmail() == null || notification.getRecipient().getEmail().isBlank()) {
            return;
        }
        if ("AUCTION_ENDED_SELLER".equals(notification.getType())
                && (notification.getActionPath() == null || !notification.getActionPath().contains("orderId="))) {
            return;
        }
        NotificationEmail email = new NotificationEmail();
        email.setNotificationId(notification.getId());
        email.setRecipientEmail(notification.getRecipient().getEmail());
        email.setSubject("Mythix: " + notification.getTitle());
        email.setBody(buildBody(notification));
        email.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC));
        outbox.save(email);
    }

    private String buildBody(UserNotification notification) {
        StringBuilder body = new StringBuilder("ORDER_SENT_BUYER".equals(notification.getType())
            ? "The seller marked your order as sent." : notification.getMessage());
        if (notification.getOrder() != null) {
            body.append("\n\nOrder: ").append(notification.getOrder().getOrderNumber());
            if ("ORDER_SENT_BUYER".equals(notification.getType())) {
                body.append("\nTracking number: ").append(notification.getOrder().getTrackingNumber());
                if (notification.getOrder().getShippingCarrier() != null) {
                    body.append("\nCarrier: ").append(notification.getOrder().getShippingCarrier());
                }
            }
        }
        String path = notification.getActionPath();
        if (path == null && notification.getOrder() != null) {
            path = switch (notification.getType()) {
                case "ORDER_RESERVED", "ORDER_CANCELLED_SELLER", "ORDER_FULFILLED_SELLER" -> "/workspace/orders";
                default -> "/orders/" + notification.getOrder().getId();
            };
        }
        if (path == null) {
            path = switch (notification.getType()) {
                case "SELLER_APPLICATION_APPROVED", "SELLER_APPLICATION_REJECTED" -> "/profile";
                case "AUCTION_CANCELLATION_DECIDED" -> "/workspace/auctions";
                default -> null;
            };
        }
        if (path != null && path.startsWith("/") && !path.startsWith("//")) {
            body.append("\n\nView details: ").append(baseUrl.replaceAll("/+$", "")).append(path);
        }
        body.append("\n\nMythix TCG");
        return body.toString();
    }

    @Scheduled(fixedDelayString = "${app.mail.poll-ms:30000}")
    public void deliverDue() {
        if (!enabled || from.isBlank()) return;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (NotificationEmail email : outbox.findDue(now, PageRequest.of(0, 50))) {
            LocalDateTime claimedAt = LocalDateTime.now(ZoneOffset.UTC);
            if (outbox.claim(email.getId(), claimedAt, claimedAt.plusMinutes(2)) != 1) continue;
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(email.getRecipientEmail());
                message.setSubject(email.getSubject());
                message.setText(email.getBody());
                mailSender.send(message);
                outbox.markSent(email.getId(), LocalDateTime.now(ZoneOffset.UTC));
            } catch (Exception error) {
                int minutes = Math.min(60, 1 << Math.min(email.getAttempts(), 5));
                String detail = error.getClass().getSimpleName() + ": " + error.getMessage();
                outbox.reschedule(email.getId(), LocalDateTime.now(ZoneOffset.UTC).plusMinutes(minutes),
                    detail.substring(0, Math.min(500, detail.length())));
                log.warn("Email delivery failed for notification {}: {}", email.getNotificationId(), detail);
            }
        }
    }
}
