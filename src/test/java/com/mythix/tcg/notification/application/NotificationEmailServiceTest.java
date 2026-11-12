package com.mythix.tcg.notification.application;

import com.mythix.tcg.notification.domain.NotificationEmail;
import com.mythix.tcg.notification.domain.UserNotification;
import com.mythix.tcg.notification.persistence.NotificationEmailRepository;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.application.InvoicePdfService;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.auth.security.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationEmailServiceTest {
    private final NotificationEmailRepository outbox = mock(NotificationEmailRepository.class);
    private final NotificationEmailDeliveryWorker worker = mock(NotificationEmailDeliveryWorker.class);
    private final SensitiveMailCipher cipher = new SensitiveMailCipher(new JwtProperties("test-secret-with-at-least-32-bytes", 60));
    private final NotificationEmailService service = new NotificationEmailService(outbox, cipher, worker);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "from", "orders@mythix.test");
        ReflectionTestUtils.setField(service, "baseUrl", "https://mythix.test");
    }

    @Test
    void sentOrderEmailContainsStructuredTrackingAndLink() {
        Order order = Order.builder().id(9L).orderNumber("ORD-9")
            .status("SENT").trackingNumber("EMS123456").shippingCarrier("Thailand Post").build();
        UserNotification notification = UserNotification.builder().recipient(User.builder().email("buyer@test.com").build())
            .order(order).type("ORDER_SENT_BUYER").title("Your order has been sent")
            .message("The seller sent your order.").actionPath("/orders/9").build();
        notification.setId(17L);

        service.enqueue(notification);

        var captured = org.mockito.ArgumentCaptor.forClass(NotificationEmail.class);
        verify(outbox).save(captured.capture());
        assertThat(captured.getValue().getRecipientEmail()).isEqualTo("buyer@test.com");
        assertThat(captured.getValue().getNotificationId()).isEqualTo(17L);
        assertThat(captured.getValue().getBody()).contains("EMS123456", "Thailand Post", "https://mythix.test/orders/9");
        assertThat(captured.getValue().getAttachmentOrderId()).isEqualTo(9L);
        assertThat(captured.getValue().getAttachmentType()).isEqualTo("application/pdf");
        assertThat(captured.getValue().getAttachmentStatusSnapshot()).isEqualTo("SENT");
    }

    @Test
    void routineOrderConfirmationDoesNotQueueEmail() {
        UserNotification notification = UserNotification.builder().recipient(User.builder().email("buyer@test.com").build())
            .type("ORDER_CONFIRMED_BUYER").title("Confirmed").message("Confirmed").build();
        notification.setId(18L);
        service.enqueue(notification);
        verifyNoInteractions(outbox);
    }

    @Test
    void dueEmailIsClaimedBeforeSendingAndMarkedSent() throws Exception {
        NotificationEmail email = new NotificationEmail();
        email.setId(4L);
        email.setNotificationId(17L);
        email.setRecipientEmail("buyer@test.com");
        email.setSubject("Order sent");
        email.setBody("Tracking: EMS123456");
        when(outbox.findDue(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(email));
        when(outbox.claim(eq(4L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

        service.deliverDue();

        verify(worker).deliver(4L, "orders@mythix.test");
    }

    @Test
    void disputeOpenedAndDecidedAreEligibleButEvidenceIsWebOnly() {
        UserNotification opened = UserNotification.builder().recipient(User.builder().email("buyer@test.com").build())
            .type("AUCTION_DISPUTE_OPENED").title("Dispute opened").message("Opened")
            .actionPath("/auction-disputes?disputeId=41").build();
        opened.setId(41L);
        service.enqueue(opened);
        verify(outbox).save(argThat(row -> row.getNotificationId().equals(41L)));

        reset(outbox);
        UserNotification evidence = UserNotification.builder().recipient(User.builder().email("buyer@test.com").build())
            .type("AUCTION_DISPUTE_EVIDENCE_ADDED").title("Evidence").message("Added")
            .actionPath("/auction-disputes?disputeId=41").build();
        evidence.setId(42L);
        service.enqueue(evidence);
        verifyNoInteractions(outbox);

        UserNotification decided = UserNotification.builder().recipient(User.builder().email("buyer@test.com").build())
            .type("AUCTION_DISPUTE_DECIDED").title("Dispute decided").message("Decision: Dismissed")
            .actionPath("/auction-disputes?disputeId=41").build();
        decided.setId(43L);
        service.enqueue(decided);
        verify(outbox).save(argThat(row -> row.getNotificationId().equals(43L)));
    }

    @Test
    void allSelectedNotificationTypesAreQueued() {
        List<String> types = List.of(
            "ORDER_RESERVED", "ORDER_PLACED_BUYER", "ORDER_CANCELLED_BUYER", "ORDER_CANCELLED_SELLER",
            "ORDER_SENT_BUYER", "SELLER_APPLICATION_APPROVED", "SELLER_APPLICATION_REJECTED",
            "AUCTION_CANCELLATION_DECIDED", "AUCTION_OUTBID", "AUCTION_WON", "AUCTION_ENDED_SELLER",
            "AUCTION_SECOND_CHANCE", "AUCTION_DEFAULTED_WARNING", "AUCTION_DISPUTE_OPENED", "AUCTION_DISPUTE_DECIDED"
        );
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            String path = switch (type) {
                case "AUCTION_ENDED_SELLER" -> "/workspace/auctions?orderId=71";
                case "AUCTION_DISPUTE_OPENED", "AUCTION_DISPUTE_DECIDED" -> "/auction-disputes?disputeId=71";
                default -> "/orders/71";
            };
            UserNotification notification = UserNotification.builder()
                .recipient(User.builder().email("buyer@test.com").build())
                .type(type).title("Update " + type).message("Message for " + type).actionPath(path).build();
            notification.setId((long) i + 100);
            service.enqueue(notification);
        }
        var captured = org.mockito.ArgumentCaptor.forClass(NotificationEmail.class);
        verify(outbox, times(types.size())).save(captured.capture());
        assertThat(captured.getAllValues()).extracting(NotificationEmail::getSubject)
            .containsExactlyElementsOf(types.stream().map(type -> "Mythix: Update " + type).toList());
    }

    @Test
    void sensitiveEmailIsEncryptedAtRestAndDelegatedToWorker() throws Exception {
        service.enqueueSensitive("buyer@test.com", "Reset", "https://example.test/reset?token=secret", LocalDateTime.now().plusMinutes(5));
        var captured = org.mockito.ArgumentCaptor.forClass(NotificationEmail.class);
        verify(outbox).save(captured.capture());
        NotificationEmail email = captured.getValue();
        email.setId(10L);
        assertThat(email.getBody()).doesNotContain("secret", "https://");
        when(outbox.findDue(any(), any())).thenReturn(List.of(email));
        when(outbox.claim(eq(10L), any(), any())).thenReturn(1);
        service.deliverDue();
        assertThat(cipher.decrypt(email.getBody())).contains("token=secret");
        verify(worker).deliver(10L, "orders@mythix.test");
    }

    @Test
    void expiredSensitiveMailIsDiscardedWithoutSending() {
        NotificationEmail email = new NotificationEmail();
        email.setId(11L);
        email.setSensitive(true);
        email.setExpiresAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
        when(outbox.findDue(any(), any())).thenReturn(List.of(email));
        when(outbox.claim(eq(11L), any(), any())).thenReturn(1);
        service.deliverDue();
        verifyNoInteractions(worker);
        verify(outbox).markSent(eq(11L), any());
    }
}
